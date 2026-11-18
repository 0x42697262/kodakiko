@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.ObjectDto
import dev.kodakiko.contract.ObjectState
import dev.kodakiko.contract.PartUrl
import dev.kodakiko.contract.PartsResponse
import dev.kodakiko.contract.PresignResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UploadedPart
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.upload.FailureReason
import dev.kodakiko.data.upload.StopReason
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadPart
import dev.kodakiko.data.upload.UploadStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import dev.kodakiko.contract.ObjectVariant as WireObjectVariant

private const val SINGLE_URL = "https://bucket.test/single"
private const val EXPIRES_AT = "2026-11-13T10:00:00.000Z"

private fun presignSingle(): JsonElement = ContractSchemas.presignResponse.encode(PresignResponse.Single(SINGLE_URL, EXPIRES_AT))

@RunWith(RobolectricTestRunner::class)
class UploadsTest : SyncTestBase() {
    private lateinit var fixture: AccountFixture
    private lateinit var local: LocalEntry
    private lateinit var fakeHttp: FakeHttp
    private lateinit var uploader: FakeUploader
    private lateinit var scope: TestScope
    private val changed: MutableList<List<String>> = CopyOnWriteArrayList()
    private val uploadedEntries: MutableList<String> = CopyOnWriteArrayList()
    private val afterUpload: MutableList<String> = CopyOnWriteArrayList()
    private val asked = AtomicInteger(0)

    @Volatile
    private var allowed: Boolean = true

    private fun TestScope.setUp() {
        scope = this
        fixture = createAccountFixture()
        local = createLocalEntry(fixture, listOf(ByteArray(100)), "n", emptyList())
        fakeHttp = FakeHttp()
        uploader = FakeUploader()
        fakeHttp.on(POST, Routes.objectsPresign()) { presignSingle() }
        fakeHttp.on(POST, Regex("/confirm$")) { request ->
            ContractSchemas.objectDto.encode(
                ObjectDto(
                    id = request.path.split("/").let { it[it.size - 2] },
                    mediaId = local.mediaIds[0],
                    variant = WireObjectVariant.ORIGINAL,
                    sizeBytes = 200,
                    state = ObjectState.UPLOADED,
                ),
            )
        }
    }

    private fun uploadsTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            setUp()
            body()
        }

    private fun objectIdOf(original: Boolean): String = if (original) local.originalObjectIds[0] else local.thumbnailObjectIds[0]

    private fun objectState(objectId: String): ObjectRow? = fixture.rowStore.transaction { it.objects.get(objectId) }

    private fun uploadStateOf(objectId: String): ObjectUploadState? = objectState(objectId)?.uploadState

    private fun coordinator(): UploadCoordinator {
        val result = DefaultUploadCoordinator(
            UploadCoordinatorDeps(
                http = fakeHttp,
                rowStore = fixture.rowStore,
                uploader = uploader,
                onEntriesChanged = { ids: List<String> -> changed += ids.toList() },
                uploadPolicy = UploadPolicy { allowed },
                thumbnails = ThumbnailUploadHook { objectId: String -> afterUpload += objectId },
                notificationPrompt = NotificationPromptPort { asked.incrementAndGet() },
                log = noLog(),
                scope = scope.backgroundScope,
                ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            ),
        )
        result.onObjectUploaded { entryId: String -> uploadedEntries += entryId }
        return result
    }

    private fun partsJson(partSize: Long, uploaded: List<UploadedPart>, urls: List<PartUrl>): JsonElement =
        ContractSchemas.partsResponse.encode(PartsResponse(partSize, uploaded, urls, EXPIRES_AT))

    private suspend fun uploading(original: Boolean = true): Pair<UploadCoordinator, String> {
        val uploads = coordinator()
        val objectId = objectIdOf(original)
        uploads.enqueueObjectUploads(listOf(objectId))
        return Pair(uploads, objectId)
    }

    @Test
    fun enqueuePresignsHandsASinglePartJobToTheUploaderAndMarksTheObjectUploading() = uploadsTest {
        val objectId = objectIdOf(true)
        val uploads = coordinator()
        uploads.enqueueObjectUploads(listOf(objectId))
        val row = objectState(objectId) ?: error("missing object")
        assertEquals(
            buildJsonObject {
                put("id", objectId)
                put("mediaId", local.mediaIds[0])
                put("variant", "original")
                put("sizeBytes", row.sizeBytes)
            },
            fakeHttp.requests[0].body,
        )
        assertEquals(listOf(ObjectUploadJob(objectId, listOf(UploadPart(0, SINGLE_URL, 0, row.sizeBytes)))), uploader.jobs)
        assertEquals(ObjectUploadState.UPLOADING, row.uploadState)
        assertTrue(changed.flatten().contains(local.entryId))
    }

    @Test
    fun enqueueBuildsMultipartPartsFromPartSizeBytesWithAShorterLastPart() = uploadsTest {
        val objectId = objectIdOf(true)
        val size = objectState(objectId)?.sizeBytes ?: 0
        val partSize = (size + 2) / 3
        fakeHttp.on(POST, Routes.objectsPresign()) {
            ContractSchemas.presignResponse.encode(
                PresignResponse.Multipart(partSize, listOf(1L, 2L, 3L).map { PartUrl(it, "https://bucket.test/p$it") }, EXPIRES_AT),
            )
        }
        coordinator().enqueueObjectUploads(listOf(objectId))
        assertEquals(
            listOf(
                UploadPart(1, "https://bucket.test/p1", 0, partSize),
                UploadPart(2, "https://bucket.test/p2", partSize, partSize),
                UploadPart(3, "https://bucket.test/p3", partSize * 2, size - partSize * 2),
            ),
            uploader.jobs[0].parts,
        )
    }

    @Test
    fun enqueueKeepsAnObjectQueuedAndCountsItAsHeldBackWhileThePolicyDeniesIt() = uploadsTest {
        allowed = false
        val objectId = objectIdOf(true)
        val uploads = coordinator()
        uploads.enqueueObjectUploads(listOf(objectId))
        assertEquals(ObjectUploadState.QUEUED, uploadStateOf(objectId))
        assertEquals(0, fakeHttp.requests.size)
        assertEquals(0, uploader.jobs.size)
        assertEquals(1, uploads.heldBackCount())
        allowed = true
        uploads.reconcileUploads()
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
        assertEquals(0, uploads.heldBackCount())
    }

    @Test
    fun enqueueCallsTheNotificationPromptWheneverItEnqueuesJobsThePromptAsksOnlyOnce() = uploadsTest {
        val uploads = coordinator()
        uploads.enqueueObjectUploads(listOf(objectIdOf(true)))
        uploads.enqueueObjectUploads(listOf(objectIdOf(false)))
        runCurrent()
        assertEquals(2, asked.get())
    }

    @Test
    fun enqueuePropagatesPresignErrorsSoTheExecutorCanClassifyThem() = uploadsTest {
        fakeHttp.on(POST, Routes.objectsPresign()) { throw apiFailure(403, ErrorCode.QUOTA_EXCEEDED) }
        val failure = expectFailure<ApiError> { coordinator().enqueueObjectUploads(listOf(objectIdOf(true))) }
        assertEquals(ErrorCode.QUOTA_EXCEEDED, failure.code)
        assertEquals(ObjectUploadState.QUEUED, uploadStateOf(objectIdOf(true)))
    }

    @Test
    fun eventsConfirmACompletedUploadAndMarkTheObjectUploaded() = uploadsTest {
        val (uploads, objectId) = uploading()
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(1, fakeHttp.count(POST, Routes.objectConfirm(objectId)))
        assertEquals(ObjectUploadState.UPLOADED, uploadStateOf(objectId))
        assertEquals(listOf(local.entryId), uploadedEntries.toList())
        assertEquals(emptyList<String>(), afterUpload.toList())
    }

    @Test
    fun eventsCallThumbnailCacheAfterUploadForThumbnailObjects() = uploadsTest {
        val (uploads, objectId) = uploading(original = false)
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(listOf(objectId), afterUpload.toList())
    }

    @Test
    fun eventsAreIgnoredForObjectsWithoutARowStoreRow() = uploadsTest {
        val uploads = coordinator()
        uploads.handleEvent(UploadEvent.Completed(fixture.randomId()))
        uploads.handleEvent(UploadEvent.Failed(fixture.randomId(), FailureReason.EXPIRED))
        assertEquals(0, fakeHttp.requests.size)
    }

    @Test
    fun eventsResumeAMultipartUploadFromTheMissingPartsOnUploadIncomplete() = uploadsTest {
        val (uploads, objectId) = uploading()
        val size = objectState(objectId)?.sizeBytes ?: 0
        val partSize = (size + 2) / 3
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw apiFailure(422, ErrorCode.UPLOAD_INCOMPLETE) }
        fakeHttp.on(GET, Routes.objectParts(objectId)) {
            partsJson(
                partSize,
                listOf(UploadedPart(1, partSize)),
                listOf(PartUrl(2, "https://bucket.test/p2"), PartUrl(3, "https://bucket.test/p3")),
            )
        }
        uploader.jobs.clear()
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(
            listOf(
                ObjectUploadJob(
                    objectId,
                    listOf(
                        UploadPart(2, "https://bucket.test/p2", partSize, partSize),
                        UploadPart(3, "https://bucket.test/p3", partSize * 2, size - partSize * 2),
                    ),
                ),
            ),
            uploader.jobs,
        )
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
    }

    @Test
    fun eventsPresignAgainOnUploadIncompleteForASinglePutObject() = uploadsTest {
        val (uploads, objectId) = uploading()
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw apiFailure(422, ErrorCode.UPLOAD_INCOMPLETE) }
        uploader.jobs.clear()
        val before = fakeHttp.count(POST, Routes.objectsPresign())
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(1, fakeHttp.count(GET, Routes.objectParts(objectId)))
        assertEquals(before + 1, fakeHttp.count(POST, Routes.objectsPresign()))
        assertEquals(1, uploader.jobs.size)
    }

    @Test
    fun eventsPresignAgainAfterFailedExpired() = uploadsTest {
        val (uploads, objectId) = uploading()
        uploader.jobs.clear()
        uploads.handleEvent(UploadEvent.Failed(objectId, FailureReason.EXPIRED))
        assertEquals(1, uploader.jobs.size)
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
    }

    @Test
    fun eventsMarkTheObjectAndItsOutboxItemDeadAfterFailedMissingFile() = uploadsTest {
        val (uploads, objectId) = uploading()
        uploads.handleEvent(UploadEvent.Failed(objectId, FailureReason.MISSING_FILE))
        assertEquals(ObjectUploadState.DEAD, uploadStateOf(objectId))
        assertEquals(OutboxState.DEAD, fixture.outbox()[0].state)
    }

    @Test
    fun eventsLeaveTheStateAloneAfterFailedNetworkOrHttpAndAfterAStoppedTimeout() = uploadsTest {
        val (uploads, objectId) = uploading()
        uploads.handleEvent(UploadEvent.Failed(objectId, FailureReason.NETWORK))
        uploads.handleEvent(UploadEvent.Failed(objectId, FailureReason.HTTP))
        uploads.handleEvent(UploadEvent.Stopped(StopReason.TIMEOUT))
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
    }

    @Test
    fun eventsMarkTheObjectDeadWhenConfirmFailsWithAClientErrorOtherThanUploadIncomplete() = uploadsTest {
        val (uploads, objectId) = uploading()
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw apiFailure(422, ErrorCode.UPLOAD_SIZE_MISMATCH) }
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(ObjectUploadState.DEAD, uploadStateOf(objectId))
    }

    @Test
    fun eventsPresignAgainAndResubmitWhenConfirmAnswers404NotFound() = uploadsTest {
        val (uploads, objectId) = uploading()
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw apiFailure(404, ErrorCode.NOT_FOUND) }
        uploader.jobs.clear()
        val before = fakeHttp.count(POST, Routes.objectsPresign())
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(before + 1, fakeHttp.count(POST, Routes.objectsPresign()))
        assertEquals(1, uploader.jobs.size)
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
    }

    @Test
    fun eventsMarkNothingAndDoNotRetryWhenConfirmAnswers401() = uploadsTest {
        val (uploads, objectId) = uploading()
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw apiFailure(401, ErrorCode.UNAUTHENTICATED) }
        uploader.jobs.clear()
        val presigns = fakeHttp.count(POST, Routes.objectsPresign())
        uploads.handleEvent(UploadEvent.Completed(objectId))
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
        assertEquals(OutboxState.QUEUED, fixture.outbox()[0].state)
        assertEquals(0, uploader.jobs.size)
        assertEquals(presigns, fakeHttp.count(POST, Routes.objectsPresign()))
    }

    @Test
    fun eventsKeepTheObjectUploadingWhenConfirmFailsWithANetworkError() = uploadsTest {
        val (uploads, objectId) = uploading()
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw Bug() }
        expectFailure<Bug> { uploads.handleEvent(UploadEvent.Completed(objectId)) }
        assertEquals(ObjectUploadState.UPLOADING, uploadStateOf(objectId))
    }

    @Test
    fun reconcileConfirmsUploadingObjectsWhoseUploaderStatusIsCompletedOrUnknown() = uploadsTest {
        val uploads = coordinator()
        val objectId = objectIdOf(true)
        uploads.enqueueObjectUploads(listOf(objectId))
        uploader.setStatus(objectId, UploadStatus.COMPLETED)
        uploads.reconcileUploads()
        assertEquals(ObjectUploadState.UPLOADED, uploadStateOf(objectId))

        val thumbnailId = objectIdOf(false)
        uploads.enqueueObjectUploads(listOf(thumbnailId))
        uploader.setStatus(thumbnailId, null)
        uploads.reconcileUploads()
        assertEquals(ObjectUploadState.UPLOADED, uploadStateOf(thumbnailId))
    }

    @Test
    fun reconcileLeavesObjectsTheUploaderIsStillWorkingOn() = uploadsTest {
        val uploads = coordinator()
        val objectId = objectIdOf(true)
        uploads.enqueueObjectUploads(listOf(objectId))
        uploader.setStatus(objectId, UploadStatus.UPLOADING)
        uploads.reconcileUploads()
        assertEquals(0, fakeHttp.count(POST, Routes.objectConfirm(objectId)))
    }

    @Test
    fun reconcileTriesToConfirmFailedUploadsAndResumesThemOnUploadIncomplete() = uploadsTest {
        val uploads = coordinator()
        val objectId = objectIdOf(true)
        uploads.enqueueObjectUploads(listOf(objectId))
        uploader.setStatus(objectId, UploadStatus.FAILED)
        fakeHttp.on(POST, Routes.objectConfirm(objectId)) { throw apiFailure(422, ErrorCode.UPLOAD_INCOMPLETE) }
        uploader.jobs.clear()
        uploads.reconcileUploads()
        assertEquals(1, uploader.jobs.size)
    }

    @Test
    fun reconcileKeepsGoingWhenOneObjectFailsToReconcile() = uploadsTest {
        val uploads = coordinator()
        uploads.enqueueObjectUploads(listOf(objectIdOf(true), objectIdOf(false)))
        fakeHttp.on(POST, Routes.objectConfirm(objectIdOf(true))) { throw Bug("boom") }
        uploader.setStatus(objectIdOf(true), UploadStatus.COMPLETED)
        uploader.setStatus(objectIdOf(false), UploadStatus.COMPLETED)
        uploads.reconcileUploads()
        assertEquals(ObjectUploadState.UPLOADED, uploadStateOf(objectIdOf(false)))
    }

    @Test
    fun reconcileRetriesEnqueueingForQueuedObjectsAndSwallowsPresignErrors() = uploadsTest {
        val objectId = objectIdOf(true)
        fixture.rowStore.transaction { tx -> tx.objects.get(objectId)?.let { tx.objects.put(it.copy(uploadState = ObjectUploadState.QUEUED)) } }
        fakeHttp.on(POST, Routes.objectsPresign()) { throw apiFailure(403, ErrorCode.QUOTA_EXCEEDED) }
        val uploads = coordinator()
        uploads.reconcileUploads()
        assertEquals(ObjectUploadState.QUEUED, uploadStateOf(objectId))
    }
}
