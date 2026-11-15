@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.service.entries.EntryKeyCache
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.entries.EntryKeysDeps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val RESOLVE_NOW = "2026-11-20T10:00:00.000Z"
private const val EARLY = "2026-11-15T10:00:00.000Z"
private const val LATE = "2026-11-16T10:00:00.000Z"

private class InterferingEntryKeys(private val inner: EntryKeys, private val onKey: suspend (String) -> Unit) : EntryKeys by inner {
    override suspend fun entryKey(entryId: String): ByteArray {
        val key = inner.entryKey(entryId)
        onKey(entryId)
        return key
    }
}

@RunWith(RobolectricTestRunner::class)
class ConflictTest : SyncTestBase() {
    private lateinit var fixture: AccountFixture
    private lateinit var local: LocalEntry
    private lateinit var ioDispatcher: CoroutineDispatcher

    private fun TestScope.setUp() {
        fixture = createAccountFixture()
        local = createLocalEntry(fixture, listOf(byteArrayOf(1)), "base", listOf("a"), enqueue = false)
        ioDispatcher = StandardTestDispatcher(testScheduler)
    }

    private fun conflictTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            setUp()
            body()
        }

    private fun seal(metadata: EntryMetadataV1): ByteArray = sealEntryMetadata(fixture, local.entryKey, local.entryId, metadata)

    private fun open(blob: ByteArray): EntryMetadataV1 = openEntryMetadataForTest(sodium, local.entryKey, local.entryId, blob)

    private fun entryRow(): EntryRow = fixture.entry(local.entryId) ?: error("missing entry")

    private fun prepareSynced(ancestor: EntryMetadataV1, localMetadata: EntryMetadataV1): ByteArray {
        val ancestorBlob = seal(ancestor)
        val localBlob = seal(localMetadata)
        fixture.updateEntry(local.entryId) {
            it.copy(revision = 1, baseRevision = 1, baseMetadataBlob = ancestorBlob, metadataBlob = localBlob, localState = EntryLocalState.PENDING_UPDATE)
        }
        return localBlob
    }

    private fun realKeys(): EntryKeys =
        EntryKeyCache(EntryKeysDeps(sodium = sodium, rowStore = fixture.rowStore, account = { fixture.keys }, ioDispatcher = ioDispatcher))

    private fun resolver(entryKeys: EntryKeys = realKeys()): ConflictResolver =
        DefaultConflictResolver(
            ConflictResolverDeps(
                sodium = sodium,
                rowStore = fixture.rowStore,
                entryKeys = entryKeys,
                clock = TestSyncClock(0) { RESOLVE_NOW },
                ioDispatcher = ioDispatcher,
            ),
        )

    @Test
    fun mergesLocalAndServerEditsStoresTheMergedBlobOnTheServerRevisionAndAsksForARetry() = conflictTest {
        val ancestor = local.metadata
        val localBlob = prepareSynced(ancestor, ancestor.copy(note = "local note", editedAtUtc = EARLY))
        val serverBlob = seal(ancestor.copy(tags = listOf("a", "b"), editedAtUtc = LATE))
        val outcome = resolver().resolve(local.entryId, ServerEntryVersion(2, serverBlob))
        assertEquals(ConflictOutcome.RETRY, outcome)
        val row = entryRow()
        assertEquals(2L, row.baseRevision)
        assertEquals(2L, row.revision)
        assertArrayEquals(serverBlob, row.baseMetadataBlob)
        assertEquals(EntryLocalState.PENDING_UPDATE, row.localState)
        assertFalse(row.metadataBlob.contentEquals(localBlob))
        val merged = open(row.metadataBlob)
        assertEquals("local note", merged.note)
        assertEquals(listOf("a", "b"), merged.tags)
        assertEquals(RESOLVE_NOW, merged.editedAtUtc)
    }

    @Test
    fun adoptsTheServerBlobAndRevisionWhenTheMergeEqualsTheServerMetadata() = conflictTest {
        val ancestor = local.metadata
        val edited = ancestor.copy(note = "edited", editedAtUtc = EARLY)
        prepareSynced(ancestor, edited)
        val serverBlob = seal(edited.copy(editedAtUtc = LATE))
        val outcome = resolver().resolve(local.entryId, ServerEntryVersion(2, serverBlob))
        assertEquals(ConflictOutcome.ADOPTED, outcome)
        val row = entryRow()
        assertArrayEquals(serverBlob, row.metadataBlob)
        assertArrayEquals(serverBlob, row.baseMetadataBlob)
        assertEquals(2L, row.revision)
        assertEquals(2L, row.baseRevision)
        assertEquals(EntryLocalState.SYNCED, row.localState)
    }

    @Test
    fun clearsTheStoredServerPendingCopyAfterResolving() = conflictTest {
        val ancestor = local.metadata
        prepareSynced(ancestor, ancestor.copy(note = "x", editedAtUtc = EARLY))
        val serverBlob = seal(ancestor.copy(note = "y", editedAtUtc = LATE))
        fixture.updateEntry(local.entryId) { it.copy(serverPendingBlob = serverBlob, serverPendingRevision = 2) }
        resolver().resolve(local.entryId, ServerEntryVersion(2, serverBlob))
        val row = entryRow()
        assertNull(row.serverPendingBlob)
        assertNull(row.serverPendingRevision)
    }

    @Test
    fun keepsTheLaterNoteWhenBothSidesChangedIt() = conflictTest {
        val ancestor = local.metadata
        prepareSynced(ancestor, ancestor.copy(note = "local", editedAtUtc = EARLY))
        val serverBlob = seal(ancestor.copy(note = "server", editedAtUtc = LATE))
        resolver().resolve(local.entryId, ServerEntryVersion(2, serverBlob))
        assertEquals("server", open(entryRow().metadataBlob).note)
    }

    @Test
    fun treatsAMissingEntryAsAdopted() = conflictTest {
        val outcome = resolver().resolve(fixture.randomId(), ServerEntryVersion(2, byteArrayOf(1)))
        assertEquals(ConflictOutcome.ADOPTED, outcome)
    }

    @Test
    fun doesNotLoseAnEditMadeWhileTheMergeWasBeingComputed() = conflictTest {
        val ancestor = local.metadata
        prepareSynced(ancestor, ancestor.copy(note = "first local edit", editedAtUtc = EARLY))
        val serverBlob = seal(ancestor.copy(tags = listOf("a", "b"), editedAtUtc = LATE))
        var interfered = false
        val interferingKeys = InterferingEntryKeys(realKeys()) { entryId ->
            if (!interfered) {
                interfered = true
                fixture.updateEntry(entryId) { it.copy(metadataBlob = seal(ancestor.copy(note = "edit during merge", editedAtUtc = LATE))) }
            }
        }
        val outcome = resolver(interferingKeys).resolve(local.entryId, ServerEntryVersion(2, serverBlob))
        assertEquals(ConflictOutcome.RETRY, outcome)
        val merged = open(entryRow().metadataBlob)
        assertEquals("edit during merge", merged.note)
        assertEquals(listOf("a", "b"), merged.tags)
        assertEquals(2L, entryRow().baseRevision)
    }

    @Test
    fun givesUpWithARetryOutcomeInsteadOfLoopingForeverWhenEditsKeepArriving() = conflictTest {
        val ancestor = local.metadata
        prepareSynced(ancestor, ancestor.copy(note = "start", editedAtUtc = EARLY))
        val serverBlob = seal(ancestor.copy(tags = listOf("a", "b"), editedAtUtc = LATE))
        var counter = 0
        val racingKeys = InterferingEntryKeys(realKeys()) { entryId ->
            counter += 1
            fixture.updateEntry(entryId) { it.copy(metadataBlob = seal(ancestor.copy(note = "edit $counter", editedAtUtc = LATE))) }
        }
        assertEquals(ConflictOutcome.RETRY, resolver(racingKeys).resolve(local.entryId, ServerEntryVersion(2, serverBlob)))
        assertTrue(counter < 20)
        assertEquals(1L, entryRow().baseRevision)
    }
}
