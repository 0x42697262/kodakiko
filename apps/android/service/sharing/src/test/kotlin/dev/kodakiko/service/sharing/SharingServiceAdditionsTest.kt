@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sharing

import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.contract.ShareListResponse
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.service.entries.EntryKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole

private const val RENAMED_AT = "2026-06-02T08:30:00.000Z"
private const val CLOCK_STEP_MS = 60_000L

private class ClearingEntryKeys(private val inner: EntryKeys, private val onCollectionKey: () -> Unit) : EntryKeys by inner {
    private val fired = AtomicBoolean(false)

    override suspend fun collectionKey(collectionId: String): ByteArray {
        if (fired.compareAndSet(false, true)) onCollectionKey()
        return inner.collectionKey(collectionId)
    }
}

@RunWith(RobolectricTestRunner::class)
class SharingServiceAdditionsTest : SharingTestBase() {
    private fun makeViewer(collectionId: String) {
        harness.fixture.rowStore.transaction { tx ->
            tx.collections.get(collectionId)?.let { tx.collections.put(it.copy(role = CollectionRole.VIEWER, ownerId = OTHER_OWNER_ID)) }
        }
    }

    @Test
    fun renameJournalPatchesANewSealedNameAndStoresTheServerTimestamp() = sharingTest {
        val id = journal("Trip")
        fake.on(PATCH, Routes.collection(id)) { request: RecordedCall ->
            val patch = ContractSchemas.collectionPatchRequest.decode(request.json())
            ContractSchemas.collectionDto.encode(
                CollectionDto(
                    id = id,
                    kind = WireCollectionKind.SHARED,
                    role = WireCollectionRole.OWNER,
                    ownerId = SERVER_OWNER_ID,
                    ownerUsername = FIXTURE_USERNAME,
                    keyMaterial = ContractSchemas.collectionCreateRequest.decode(fake.find(POST, Routes.collections()).first().json()).keyByOwner,
                    metadataBlob = patch.metadataBlob,
                    lastSeq = 3,
                    createdAt = NOW,
                    metaUpdatedAt = RENAMED_AT,
                    recipientCount = 0,
                ),
            )
        }
        harness.clock.advance(CLOCK_STEP_MS)
        service.renameJournal(id, "Renamed")
        val blob = decodeBytes(ContractSchemas.collectionPatchRequest.decode(fake.find(PATCH, Routes.collection(id)).single().json()).metadataBlob)
        val metadata = openCollectionName(harness.entryKeys.collectionKey(id), id, blob)
        assertEquals("Renamed", metadata.name)
        assertEquals(harness.clock.nowIso(), metadata.createdAtUtc)
        val row = harness.collectionRow(id)
        assertNotNull(row)
        assertArrayEquals(blob, row!!.metadataBlob)
        assertEquals(RENAMED_AT, row.metaUpdatedAt)
        assertEquals(0L, row.serverLastSeq)
        assertEquals(listOf(emptyList<String>(), emptyList()), changed.toList())
    }

    @Test
    fun ownerOnlyOperationsRefuseAViewerOrUnknownJournalWithoutCallingTheServer() = sharingTest {
        val id = journal()
        val entryId = syncedEntry(1)
        makeViewer(id)
        val recipient = Recipient(RECIPIENT_ID, "bea", sodium.boxKeypair().publicKey, "")
        val before = fake.requests.size
        assertEquals(SharingErrorReason.NOT_OWNER, expectFailure<SharingException> { service.renameJournal(id, "x") }.reason)
        assertEquals(SharingErrorReason.NOT_OWNER, expectFailure<SharingException> { service.deleteJournal(id) }.reason)
        assertEquals(SharingErrorReason.NOT_OWNER, expectFailure<SharingException> { service.addEntries(id, listOf(entryId)) }.reason)
        assertEquals(SharingErrorReason.NOT_OWNER, expectFailure<SharingException> { service.removeEntries(id, listOf(entryId)) }.reason)
        assertEquals(SharingErrorReason.NOT_OWNER, expectFailure<SharingException> { service.share(id, recipient) }.reason)
        assertEquals(SharingErrorReason.NOT_OWNER, expectFailure<SharingException> { service.renameJournal("Z".repeat(22), "x") }.reason)
        assertEquals(before, fake.requests.size)
    }

    @Test
    fun aLockedAccountIsRefusedAfterTheDisabledCheck() = sharingTest {
        signedIn = false
        assertEquals(SharingErrorReason.LOCKED, expectFailure<SharingException> { journal() }.reason)
        assertEquals(SharingErrorReason.LOCKED, expectFailure<SharingException> { service.lookupRecipient("bea") }.reason)
        assertEquals(SharingErrorReason.LOCKED, expectFailure<SharingException> { service.leave("Z".repeat(22)) }.reason)
        sharingEnabled = false
        assertEquals(SharingErrorReason.DISABLED, expectFailure<SharingException> { journal() }.reason)
        assertEquals(0, fake.requests.size)
        assertEquals("", service.myFingerprint())
    }

    @Test
    fun lookupRecipientReturnsTheServerIdentityAndMapsOfflineFailures() = sharingTest {
        val recipient = sodium.boxKeypair()
        fake.on(GET, Routes.userPublicKey("bea")) { publicKeyJson(RECIPIENT_ID, "bea", recipient.publicKey) }
        val found: Recipient = service.lookupRecipient("  BEA ")
        assertEquals(RECIPIENT_ID, found.userId)
        assertEquals("bea", found.username)
        assertArrayEquals(recipient.publicKey, found.publicKey)
        fake.on(GET, Routes.userPublicKey("bea")) { throw NetworkError() }
        assertEquals(SharingErrorReason.OFFLINE, expectFailure<SharingException> { service.lookupRecipient("bea") }.reason)
    }

    @Test
    fun listSharesReturnsTheServerItemsAndUnshareDeletesTheRecipient() = sharingTest {
        val id = journal()
        val share = ShareDto(RECIPIENT_ID, "bea", NOW)
        fake.on(GET, Routes.collectionShares(id)) { ContractSchemas.shareListResponse.encode(ShareListResponse(listOf(share))) }
        fake.on(DELETE, Routes.collectionShare(id, RECIPIENT_ID)) { null }
        assertEquals(listOf(share), service.listShares(id))
        service.unshare(id, RECIPIENT_ID)
        assertEquals(1, fake.count(DELETE, Routes.collectionShare(id, RECIPIENT_ID)))
        assertEquals(listOf(emptyList<String>(), emptyList()), changed.toList())
        fake.on(GET, Routes.collectionShares(id)) { throw NetworkError() }
        assertEquals(SharingErrorReason.OFFLINE, expectFailure<SharingException> { service.listShares(id) }.reason)
    }

    @Test
    fun addEntriesReportsForeignAndServerMissingEntriesAsFailedAndRethrowsOtherErrors() = sharingTest {
        val id = journal()
        val foreign = syncedEntry(1)
        val missingOnServer = syncedEntry(2)
        val serverError = syncedEntry(3)
        val offline = syncedEntry(4)
        harness.fixture.rowStore.transaction { tx -> tx.entries.get(foreign)?.let { tx.entries.put(it.copy(ownerId = OTHER_OWNER_ID)) } }
        fake.on(POST, Routes.collectionEntries(id)) { request: RecordedCall ->
            when (request.string("entryId")) {
                missingOnServer -> throw apiFailure(404, ErrorCode.NOT_FOUND)
                serverError -> throw apiFailure(500, ErrorCode.INTERNAL)
                offline -> throw NetworkError()
                else -> emptyObject()
            }
        }
        val changesBefore = changed.size
        val result: AddEntriesResult = service.addEntries(id, listOf(foreign, missingOnServer))
        assertEquals(AddEntriesResult(added = emptyList(), alreadyIn = emptyList(), notSynced = emptyList(), failed = listOf(foreign, missingOnServer)), result)
        assertEquals(1, fake.count(POST, Routes.collectionEntries(id)))
        assertEquals(changesBefore, changed.size)
        assertEquals(500, expectFailure<ApiError> { service.addEntries(id, listOf(serverError)) }.status)
        assertEquals(SharingErrorReason.OFFLINE, expectFailure<SharingException> { service.addEntries(id, listOf(offline)) }.reason)
        assertEquals(emptyList<MembershipRow>(), harness.fixture.rowStore.transaction { it.memberships.byCollection(id) })
    }

    @Test
    fun removeEntriesReportsTheRequestedEntriesAndStopsAtTheFirstServerFailure() = sharingTest {
        val id = journal()
        val first = syncedEntry(1)
        val second = syncedEntry(2)
        fake.on(POST, Routes.collectionEntries(id)) { emptyObject() }
        fake.on(DELETE, Routes.collectionEntry(id, first)) { null }
        service.addEntries(id, listOf(first, second))
        val changesBefore = changed.size
        assertEquals(404, expectFailure<ApiError> { service.removeEntries(id, listOf(first, second)) }.status)
        assertNull(harness.fixture.rowStore.transaction { it.memberships.get(id, first) })
        assertNotNull(harness.fixture.rowStore.transaction { it.memberships.get(id, second) })
        assertEquals(changesBefore, changed.size)
        fake.on(DELETE, Routes.collectionEntry(id, second)) { null }
        service.removeEntries(id, listOf(first, second))
        assertEquals(listOf(first, second), changed.last())
        assertEquals(emptyList<MembershipRow>(), harness.fixture.rowStore.transaction { it.memberships.byCollection(id) })
    }

    @Test
    fun deleteJournalReportsTheEntriesThatWereInIt() = sharingTest {
        val id = journal()
        val entryId = syncedEntry(1)
        fake.on(POST, Routes.collectionEntries(id)) { emptyObject() }
        fake.on(DELETE, Routes.collection(id)) { null }
        service.addEntries(id, listOf(entryId))
        service.deleteJournal(id)
        assertEquals(listOf(entryId), changed.last())
        assertEquals(1, fake.count(DELETE, Routes.collection(id)))
    }

    @Test
    fun deleteJournalKeepsTheLocalRowsWhenTheServerRefuses() = sharingTest {
        val id = journal()
        val entryId = syncedEntry(1)
        fake.on(POST, Routes.collectionEntries(id)) { emptyObject() }
        fake.on(DELETE, Routes.collection(id)) { throw NetworkError() }
        service.addEntries(id, listOf(entryId))
        assertEquals(SharingErrorReason.OFFLINE, expectFailure<SharingException> { service.deleteJournal(id) }.reason)
        assertNotNull(harness.collectionRow(id))
        assertNotNull(harness.fixture.rowStore.transaction { it.memberships.get(id, entryId) })
    }

    @Test
    fun leaveReportsTheJournalEntriesAndDeletesTheFilesOfDroppedEntriesOnly() = sharingTest {
        val id = journal()
        val onlyShared = syncedEntry(5)
        val ownToo = syncedEntry(6)
        val rowStore = harness.fixture.rowStore
        rowStore.transaction { tx ->
            val collection = tx.collections.get(id) ?: error("missing")
            tx.collections.put(collection.copy(role = CollectionRole.VIEWER, ownerId = OTHER_OWNER_ID))
            for (entryId in listOf(onlyShared, ownToo)) tx.memberships.put(MembershipRow(id, entryId, byteArrayOf(1), 1))
            tx.memberships.delete(harness.fixture.homeCollectionId, onlyShared)
        }
        val droppedFiles = harness.objectsOf(onlyShared).map { it.id }
        val keptFiles = harness.objectsOf(ownToo).map { it.id }
        fake.on(DELETE, Routes.collectionShare(id, harness.fixture.userId)) { null }
        service.leave(id)
        assertEquals(setOf(onlyShared, ownToo), changed.last().toSet())
        assertEquals(2, changed.last().size)
        droppedFiles.forEach { assertNull(harness.fixture.fileStore.size(it)) }
        keptFiles.forEach { assertNotNull(harness.fixture.fileStore.size(it)) }
        assertNotNull(rowStore.transaction { it.memberships.get(harness.fixture.homeCollectionId, ownToo) })
    }

    @Test
    fun leaveKeepsEverythingWhenTheServerRefuses() = sharingTest {
        val id = journal()
        fake.on(DELETE, Routes.collectionShare(id, harness.fixture.userId)) { throw NetworkError() }
        assertEquals(SharingErrorReason.OFFLINE, expectFailure<SharingException> { service.leave(id) }.reason)
        assertNotNull(harness.collectionRow(id))
    }
}

@RunWith(RobolectricTestRunner::class)
class CollectionNamesAdditionsTest : SharingTestBase() {
    @Test
    fun anUnknownCollectionIsUnnamed() = sharingTest {
        val names: CollectionNames = DefaultCollectionNames(
            CollectionNamesDeps(sodium = sodium, rowStore = harness.fixture.rowStore, entryKeys = harness.entryKeys, ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
        )
        assertEquals(CollectionName.Unnamed, names.get("Z".repeat(22)))
    }

    @Test
    fun aNameOpenedWhileTheCacheIsClearedIsNotCached() = sharingTest {
        val id = journal("Trip")
        lateinit var names: CollectionNames
        val keys: EntryKeys = ClearingEntryKeys(harness.entryKeys) { names.clear() }
        names = DefaultCollectionNames(
            CollectionNamesDeps(sodium = sodium, rowStore = harness.fixture.rowStore, entryKeys = keys, ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
        )
        assertEquals(CollectionName.Named("Trip"), names.get(id))
        val renamed = sealCollectionName(harness.entryKeys.collectionKey(id), id, "Renamed", NOW)
        harness.fixture.rowStore.transaction { tx -> tx.collections.get(id)?.let { tx.collections.put(it.copy(metadataBlob = renamed)) } }
        assertEquals(CollectionName.Named("Renamed"), names.get(id))
        assertEquals(CollectionName.Named("Renamed"), names.get(id))
    }
}
