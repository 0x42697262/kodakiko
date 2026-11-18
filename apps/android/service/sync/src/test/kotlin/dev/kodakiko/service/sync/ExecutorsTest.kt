@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.MediaDto
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.sync.PushFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class ExecutorsTest : SyncTestBase() {
    private lateinit var fixture: AccountFixture
    private lateinit var local: LocalEntry
    private lateinit var fakeHttp: FakeHttp
    private lateinit var deps: ExecutorDeps
    private val enqueued: MutableList<List<String>> = CopyOnWriteArrayList()
    private val resolveCalls: MutableList<Pair<String, Long>> = CopyOnWriteArrayList()
    private val changed: MutableList<String> = CopyOnWriteArrayList()
    private val logged: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    private var resolveOutcome: ConflictOutcome = ConflictOutcome.ADOPTED

    private fun TestScope.setUp() {
        fixture = createAccountFixture()
        local = createLocalEntry(fixture, listOf(byteArrayOf(1, 2, 3)), "first", listOf("a"))
        fakeHttp = FakeHttp()
        deps = ExecutorDeps(
            http = fakeHttp,
            rowStore = fixture.rowStore,
            files = fixture.files,
            onEntriesChanged = { ids: List<String> -> changed += ids },
            log = SyncLog { event: String, _: Map<String, Any?> -> logged += event },
            uploads = ObjectUploadEnqueuer { ids: List<String> ->
                enqueued += ids.toList()
                fixture.rowStore.transaction { tx ->
                    for (id in ids) tx.objects.get(id)?.let { tx.objects.put(it.copy(uploadState = ObjectUploadState.UPLOADING)) }
                }
            },
            conflict = ConflictResolver { entryId: String, server: ServerEntryVersion ->
                resolveCalls += entryId to server.revision
                resolveOutcome
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private fun executorTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            setUp()
            body()
        }

    private suspend fun runExecutor(kind: OutboxKind, payload: JsonElement = entryPayload(local.entryId)): ExecutorResult =
        PushExecutors.ALL.getValue(kind).execute(outboxRowOf(kind.wire, payload), deps)

    private fun entryRow(): EntryRow = fixture.entry(local.entryId) ?: error("missing entry")

    private fun httpFailure(status: Int, code: ErrorCode): ExecutorResult = ExecutorResult.Failed(PushFailure.Http(status, code, null))

    private fun conflictDetails(revision: Long, blob: ByteArray): JsonElement = buildJsonObject {
        put("revision", revision)
        put("metadataBlob", encodeBytes(blob))
    }

    private fun prepareSynced(): ByteArray {
        val row = entryRow()
        fixture.updateEntry(local.entryId) {
            it.copy(localState = EntryLocalState.SYNCED, revision = 1, baseRevision = 1, baseMetadataBlob = it.metadataBlob)
        }
        return row.metadataBlob
    }

    private fun editNote(note: String): ByteArray {
        val blob = sealEntryMetadata(fixture, local.entryKey, local.entryId, local.metadata.copy(note = note))
        fixture.updateEntry(local.entryId) { it.copy(metadataBlob = blob, localState = EntryLocalState.PENDING_UPDATE) }
        return blob
    }

    @Test
    fun createEntryPostsTheCurrentBlobMembershipsAndMediaThenRecordsTheBaseCopy() = executorTest {
        val before = entryRow()
        fakeHttp.on(POST, Routes.entries()) { entryDtoJson(local.entryId, 1, before.metadataBlob) }
        val result = runExecutor(OutboxKind.CREATE_ENTRY)
        val body = fakeHttp.bodyAt(0)
        assertEquals(local.entryId, body.string("id"))
        assertEquals("journal", body.string("purpose"))
        assertArrayEquals(before.metadataBlob, decodeBytes(body.string("metadataBlob")))
        assertEquals(listOf(fixture.homeCollectionId), body.getValue("memberships").jsonArray.map { it.jsonObject.string("collectionId") })
        assertEquals(local.mediaIds, body.getValue("media").jsonArray.map { it.jsonObject.string("mediaId") })
        val after = entryRow()
        assertEquals(1L, after.baseRevision)
        assertArrayEquals(before.metadataBlob, after.baseMetadataBlob)
        assertEquals(ExecutorResult.Waiting, result)
    }

    @Test
    fun createEntryEnqueuesUploadsForLocalObjectsAndWaitsUntilAllAreUploaded() = executorTest {
        val before = entryRow()
        fakeHttp.on(POST, Routes.entries()) { entryDtoJson(local.entryId, 1, before.metadataBlob) }
        val result = runExecutor(OutboxKind.CREATE_ENTRY)
        assertEquals(1, enqueued.size)
        assertEquals((local.originalObjectIds + local.thumbnailObjectIds).sorted(), enqueued[0].sorted())
        assertEquals(ExecutorResult.Waiting, result)
        assertEquals(EntryLocalState.PENDING_CREATE, entryRow().localState)
    }

    @Test
    fun createEntryFinishesAsSyncedWhenEveryObjectIsUploadedAndTheBlobIsUnchanged() = executorTest {
        val before = entryRow()
        fakeHttp.on(POST, Routes.entries()) { entryDtoJson(local.entryId, 1, before.metadataBlob) }
        runExecutor(OutboxKind.CREATE_ENTRY)
        fixture.setObjectStates(ObjectUploadState.UPLOADED)
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.CREATE_ENTRY))
        assertEquals(EntryLocalState.SYNCED, entryRow().localState)
        assertEquals(1, fakeHttp.count(POST, Routes.entries()))
    }

    @Test
    fun createEntryMarksTheEntryPendingUpdateWhenItWasEditedAfterThePost() = executorTest {
        val before = entryRow()
        fakeHttp.on(POST, Routes.entries()) { entryDtoJson(local.entryId, 1, before.metadataBlob) }
        runExecutor(OutboxKind.CREATE_ENTRY)
        fixture.setObjectStates(ObjectUploadState.UPLOADED)
        val edited = sealEntryMetadata(fixture, local.entryKey, local.entryId, local.metadata.copy(note = "edited"))
        fixture.updateEntry(local.entryId) { it.copy(metadataBlob = edited) }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.CREATE_ENTRY))
        val after = entryRow()
        assertEquals(EntryLocalState.PENDING_UPDATE, after.localState)
        assertArrayEquals(before.metadataBlob, after.baseMetadataBlob)
    }

    @Test
    fun createEntrySkipsThePostWhenTheBaseRevisionIsAlready1() = executorTest {
        fixture.updateEntry(local.entryId) { it.copy(baseRevision = 1, baseMetadataBlob = it.metadataBlob) }
        fixture.setObjectStates(ObjectUploadState.UPLOADED)
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.CREATE_ENTRY))
        assertEquals(0, fakeHttp.requests.size)
    }

    @Test
    fun createEntrySkipsThePostAndAdoptsThePulledServerCopyAsTheBaseWhenOneIsStored() = executorTest {
        val serverBlob = byteArrayOf(4, 4, 4)
        fixture.updateEntry(local.entryId) { it.copy(serverPendingBlob = serverBlob, serverPendingRevision = 1) }
        val result = runExecutor(OutboxKind.CREATE_ENTRY)
        assertEquals(0, fakeHttp.count(POST, Routes.entries()))
        val after = entryRow()
        assertEquals(1L, after.baseRevision)
        assertEquals(1L, after.revision)
        assertArrayEquals(serverBlob, after.baseMetadataBlob)
        assertNull(after.serverPendingBlob)
        assertNull(after.serverPendingRevision)
        assertEquals(ExecutorResult.Waiting, result)
        fixture.setObjectStates(ObjectUploadState.UPLOADED)
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.CREATE_ENTRY))
        assertEquals(EntryLocalState.PENDING_UPDATE, entryRow().localState)
    }

    @Test
    fun createEntryAdoptsTheServerCopyAfter409IdConflictWhenTheEntryIsOwnedAndContinues() = executorTest {
        val serverBlob = byteArrayOf(6, 6)
        fakeHttp.on(POST, Routes.entries()) { throw apiFailure(409, ErrorCode.ID_CONFLICT) }
        fakeHttp.on(GET, Routes.entry(local.entryId)) { entryDtoJson(local.entryId, 1, serverBlob) }
        fixture.updateEntry(local.entryId) { it.copy(ownerId = OWNER_ID) }
        val before = entryRow()
        val result = runExecutor(OutboxKind.CREATE_ENTRY)
        assertEquals(1, fakeHttp.count(GET, Routes.entry(local.entryId)))
        val after = entryRow()
        assertEquals(1L, after.baseRevision)
        assertArrayEquals(serverBlob, after.baseMetadataBlob)
        assertArrayEquals(before.metadataBlob, after.metadataBlob)
        assertEquals(ExecutorResult.Waiting, result)
        assertEquals(1, enqueued.size)
    }

    @Test
    fun createEntryReportsIdConflictAsAFailureWhenTheExistingEntryBelongsToSomeoneElse() = executorTest {
        fixture.updateEntry(local.entryId) { it.copy(ownerId = "someone-else-locally") }
        fakeHttp.on(POST, Routes.entries()) { throw apiFailure(409, ErrorCode.ID_CONFLICT) }
        fakeHttp.on(GET, Routes.entry(local.entryId)) { entryDtoJson(local.entryId, 1, byteArrayOf(6)) }
        assertEquals(httpFailure(409, ErrorCode.ID_CONFLICT), runExecutor(OutboxKind.CREATE_ENTRY))
        assertEquals(0L, entryRow().baseRevision)
    }

    @Test
    fun createEntryReportsHttpFailuresAsPushFailures() = executorTest {
        fakeHttp.on(POST, Routes.entries()) { throw apiFailure(403, ErrorCode.QUOTA_EXCEEDED) }
        assertEquals(httpFailure(403, ErrorCode.QUOTA_EXCEEDED), runExecutor(OutboxKind.CREATE_ENTRY))
    }

    @Test
    fun createEntryReportsNetworkErrorsAsPushFailures() = executorTest {
        fakeHttp.on(POST, Routes.entries()) { throw NetworkError() }
        assertEquals(ExecutorResult.Failed(PushFailure.Network), runExecutor(OutboxKind.CREATE_ENTRY))
    }

    @Test
    fun createEntryDoesNothingWhenTheEntryNoLongerExistsLocally() = executorTest {
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.CREATE_ENTRY, entryPayload(fixture.randomId())))
        assertEquals(0, fakeHttp.requests.size)
    }

    @Test
    fun createEntryPropagatesUnexpectedErrors() = executorTest {
        fakeHttp.on(POST, Routes.entries()) { throw Bug() }
        expectFailure<Bug> { runExecutor(OutboxKind.CREATE_ENTRY) }
    }

    @Test
    fun updateEntryMetaDoesNotCallTheServerWhenTheBlobEqualsTheBaseBlob() = executorTest {
        prepareSynced()
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(0, fakeHttp.requests.size)
    }

    @Test
    fun updateEntryMetaPatchesTheCurrentBlobWithTheBaseRevisionAndRecordsThePostedCopy() = executorTest {
        prepareSynced()
        val edited = editNote("changed")
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) { entryDtoJson(local.entryId, 2, edited) }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(
            buildJsonObject {
                put("metadataBlob", encodeBytes(edited))
                put("baseRevision", 1)
            },
            fakeHttp.requests[0].body,
        )
        val after = entryRow()
        assertEquals(2L, after.baseRevision)
        assertEquals(2L, after.revision)
        assertArrayEquals(edited, after.baseMetadataBlob)
        assertEquals(EntryLocalState.SYNCED, after.localState)
    }

    @Test
    fun updateEntryMetaReRunsImmediatelyWhenTheUserEditedWhileThePatchWasInFlight() = executorTest {
        prepareSynced()
        val first = editNote("first edit")
        var second: ByteArray? = null
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) { request ->
            val count = fakeHttp.count(PATCH, Routes.entry(local.entryId))
            if (count == 1) second = editNote("second edit")
            val body = request.body?.jsonObject ?: error("no body")
            entryDtoJson(local.entryId, (count + 1).toLong(), decodeBytes(body.string("metadataBlob")))
        }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(2, fakeHttp.count(PATCH, Routes.entry(local.entryId)))
        assertEquals(encodeBytes(first), fakeHttp.bodyAt(0).string("metadataBlob"))
        assertEquals(encodeBytes(second ?: ByteArray(0)), fakeHttp.bodyAt(1).string("metadataBlob"))
        assertEquals(JsonPrimitive(2), fakeHttp.bodyAt(1)["baseRevision"])
        assertEquals(EntryLocalState.SYNCED, entryRow().localState)
    }

    @Test
    fun updateEntryMetaResolvesARevisionConflictThroughTheResolverAndRetriesOnce() = executorTest {
        prepareSynced()
        editNote("mine")
        resolveOutcome = ConflictOutcome.RETRY
        val serverBlob = byteArrayOf(9, 9, 9)
        var calls = 0
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) {
            calls += 1
            if (calls == 1) throw apiFailure(409, ErrorCode.REVISION_CONFLICT, conflictDetails(2, serverBlob))
            entryDtoJson(local.entryId, 3, byteArrayOf(1))
        }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(listOf(local.entryId to 2L), resolveCalls.toList())
        assertEquals(2, calls)
    }

    @Test
    fun updateEntryMetaRejectsAConflictThatReportsARevisionNotNewerThanTheBaseLogsItAndDoesNotMerge() = executorTest {
        prepareSynced()
        editNote("mine")
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) {
            throw apiFailure(409, ErrorCode.REVISION_CONFLICT, conflictDetails(1, byteArrayOf(7)))
        }
        assertEquals(httpFailure(409, ErrorCode.REVISION_CONFLICT), runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(emptyList<Pair<String, Long>>(), resolveCalls.toList())
        assertEquals(listOf("sync.rollback_rejected"), logged.toList())
    }

    @Test
    fun updateEntryMetaCompletesWithoutASecondPatchWhenTheResolverAdoptsTheServerBlob() = executorTest {
        prepareSynced()
        editNote("mine")
        resolveOutcome = ConflictOutcome.ADOPTED
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) {
            throw apiFailure(409, ErrorCode.REVISION_CONFLICT, conflictDetails(2, byteArrayOf(7)))
        }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(1, fakeHttp.count(PATCH, Routes.entry(local.entryId)))
    }

    @Test
    fun updateEntryMetaReturnsAConflictFailureWhenTheRetriedPatchConflictsAgain() = executorTest {
        prepareSynced()
        editNote("mine")
        resolveOutcome = ConflictOutcome.RETRY
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) {
            throw apiFailure(409, ErrorCode.REVISION_CONFLICT, conflictDetails(2, byteArrayOf(7)))
        }
        assertEquals(httpFailure(409, ErrorCode.REVISION_CONFLICT), runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(1, resolveCalls.size)
    }

    @Test
    fun updateEntryMetaSkipsThePatchAndMergesStraightAwayWhenANewerServerCopyIsAlreadyStored() = executorTest {
        prepareSynced()
        editNote("mine")
        fixture.updateEntry(local.entryId) { it.copy(serverPendingBlob = byteArrayOf(5), serverPendingRevision = 4) }
        resolveOutcome = ConflictOutcome.ADOPTED
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.UPDATE_ENTRY_META))
        assertEquals(listOf(local.entryId to 4L), resolveCalls.toList())
        assertEquals(0, fakeHttp.requests.size)
    }

    @Test
    fun updateEntryMetaMapsA404ToAFailureTheDriverClassifiesAsDrop() = executorTest {
        prepareSynced()
        editNote("mine")
        fakeHttp.on(PATCH, Routes.entry(local.entryId)) { throw apiFailure(404, ErrorCode.NOT_FOUND) }
        assertEquals(httpFailure(404, ErrorCode.NOT_FOUND), runExecutor(OutboxKind.UPDATE_ENTRY_META))
    }

    @Test
    fun addMediaPostsTheMediaRowThenEnqueuesOnlyThatMediasObjects() = executorTest {
        val extra = createLocalEntry(fixture, listOf(byteArrayOf(1), byteArrayOf(2)), "", emptyList(), enqueue = false)
        val mediaId = extra.mediaIds[1]
        fakeHttp.on(POST, Routes.entryMedia(extra.entryId)) {
            ContractSchemas.mediaDto.encode(MediaDto(id = mediaId, entryId = extra.entryId, mediaKeyByEntry = "AAAA", createdAt = NOW_ISO))
        }
        val result = runExecutor(OutboxKind.ADD_MEDIA, entryMediaPayload(extra.entryId, mediaId))
        assertEquals(ExecutorResult.Waiting, result)
        assertEquals(mediaId, fakeHttp.bodyAt(0).string("mediaId"))
        assertEquals(listOf(extra.originalObjectIds[1], extra.thumbnailObjectIds[1]).sorted(), enqueued[0].sorted())
    }

    @Test
    fun addMediaIsDoneOnceTheMediaObjectsAreUploadedAndDoesNotPostAgain() = executorTest {
        val mediaId = local.mediaIds[0]
        fixture.setObjectStates(ObjectUploadState.UPLOADED)
        val result = runExecutor(OutboxKind.ADD_MEDIA, entryMediaPayload(local.entryId, mediaId))
        assertEquals(ExecutorResult.Done, result)
        assertEquals(0, fakeHttp.requests.size)
    }

    @Test
    fun removeMediaDeletesTheMediaOnTheServer() = executorTest {
        val mediaId = local.mediaIds[0]
        fakeHttp.on(DELETE, Routes.media(mediaId)) { null }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.REMOVE_MEDIA, entryMediaPayload(local.entryId, mediaId)))
        assertEquals(1, fakeHttp.count(DELETE, Routes.media(mediaId)))
    }

    @Test
    fun removeMediaCountsA404AsDone() = executorTest {
        val mediaId = local.mediaIds[0]
        fakeHttp.on(DELETE, Routes.media(mediaId)) { throw apiFailure(404, ErrorCode.NOT_FOUND) }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.REMOVE_MEDIA, entryMediaPayload(local.entryId, mediaId)))
    }

    @Test
    fun trashEntryAndRestoreEntryPostToTheTrashAndRestoreRoutes() = executorTest {
        val blob = entryRow().metadataBlob
        fakeHttp.on(POST, Routes.entryTrash(local.entryId)) { entryDtoJson(local.entryId, 1, blob, NOW_ISO) }
        fakeHttp.on(POST, Routes.entryRestore(local.entryId)) { entryDtoJson(local.entryId, 1, blob) }
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.TRASH_ENTRY))
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.RESTORE_ENTRY))
        assertEquals(listOf(Routes.entryTrash(local.entryId), Routes.entryRestore(local.entryId)), fakeHttp.requests.map { it.path })
    }

    @Test
    fun trashEntryReportsA404AsAFailureForTheDriverToClassify() = executorTest {
        assertEquals(httpFailure(404, ErrorCode.NOT_FOUND), runExecutor(OutboxKind.TRASH_ENTRY))
    }

    @Test
    fun deleteEntryDeletesOnTheServerThenRemovesEveryLocalRowFileAndOutboxItemOfTheEntry() = executorTest {
        fakeHttp.on(DELETE, Routes.entry(local.entryId)) { null }
        val files = local.originalObjectIds + local.thumbnailObjectIds
        assertEquals(ExecutorResult.Done, runExecutor(OutboxKind.DELETE_ENTRY))
        fixture.rowStore.transaction { tx ->
            assertNull(tx.entries.get(local.entryId))
            assertEquals(emptyList<Any>(), tx.media.byEntry(local.entryId))
            assertEquals(emptyList<Any>(), tx.memberships.byEntry(local.entryId))
            assertEquals(emptyList<Any>(), tx.objects.all())
            assertEquals(emptyList<Any>(), tx.outbox.all())
        }
        for (objectId in files) assertNull(fixture.fileStore.size(objectId))
        assertTrue(changed.contains(local.entryId))
    }

    @Test
    fun deleteEntryKeepsLocalDataWhenTheServerCallFails() = executorTest {
        fakeHttp.on(DELETE, Routes.entry(local.entryId)) { throw NetworkError() }
        assertEquals(ExecutorResult.Failed(PushFailure.Network), runExecutor(OutboxKind.DELETE_ENTRY))
        assertNotNull(fixture.entry(local.entryId))
    }
}
