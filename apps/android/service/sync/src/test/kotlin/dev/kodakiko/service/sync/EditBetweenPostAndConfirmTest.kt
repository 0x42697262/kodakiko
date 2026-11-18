@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.domain.EntryEdit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditBetweenPostAndConfirmTest : SyncTestBase() {
    @Test
    fun postsBlobAThenPatchesBlobBAndEndsSyncedWithBaseB() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val harness = EntriesHarness(context, sodium, createAccountFixture(), ioDispatcher)
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "A")
        val blobA = harness.entryRow(entryId).metadataBlob
        val http = FakeHttp()
        val deps = ExecutorDeps(
            http = http,
            rowStore = harness.fixture.rowStore,
            files = harness.files,
            onEntriesChanged = {},
            log = noLog(),
            uploads = ObjectUploadEnqueuer { _: List<String> -> },
            conflict = ConflictResolver { _: String, _: ServerEntryVersion -> ConflictOutcome.ADOPTED },
            ioDispatcher = ioDispatcher,
        )
        http.on(POST, Routes.entries()) { entryDtoJson(entryId, 1, blobA) }
        val createItem = harness.outbox().firstOrNull { it.kind == OutboxKind.CREATE_ENTRY.wire } ?: error("createEntry item missing")

        assertEquals(ExecutorResult.Waiting, PushExecutors.ALL.getValue(OutboxKind.CREATE_ENTRY).execute(createItem, deps))

        harness.clock.advance(STEP_MS)
        harness.service.edit(entryId, EntryEdit(note = "B"))
        val blobB = harness.entryRow(entryId).metadataBlob
        assertFalse(blobA.contentEquals(blobB))
        harness.fixture.rowStore.transaction { tx ->
            tx.objects.all().forEach { tx.objects.put(it.copy(uploadState = ObjectUploadState.UPLOADED)) }
        }
        http.on(PATCH, Routes.entry(entryId)) { entryDtoJson(entryId, 2, blobB) }

        assertEquals(ExecutorResult.Done, PushExecutors.ALL.getValue(OutboxKind.CREATE_ENTRY).execute(createItem, deps))
        assertEquals(EntryLocalState.PENDING_UPDATE, harness.entryRow(entryId).localState)
        val updateItem = harness.outbox().firstOrNull { it.kind == OutboxKind.UPDATE_ENTRY_META.wire } ?: error("updateEntryMeta item missing")
        assertEquals(ExecutorResult.Done, PushExecutors.ALL.getValue(OutboxKind.UPDATE_ENTRY_META).execute(updateItem, deps))

        assertEquals(listOf(POST, PATCH), http.requests.map { it.method })
        assertEquals(encodeBytes(blobA), http.bodyAt(0).string("metadataBlob"))
        assertEquals(
            buildJsonObject {
                put("metadataBlob", encodeBytes(blobB))
                put("baseRevision", 1)
            },
            http.requests[1].body,
        )
        val final = harness.entryRow(entryId)
        assertEquals(EntryLocalState.SYNCED, final.localState)
        assertEquals(2L, final.baseRevision)
        assertArrayEquals(blobB, final.baseMetadataBlob)
    }
}
