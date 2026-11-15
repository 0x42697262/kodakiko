package dev.kodakiko.service.sync

import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.PresignRequest
import dev.kodakiko.contract.PresignResponse
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.net.SyncCalls
import dev.kodakiko.data.upload.FailureReason
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadPart
import dev.kodakiko.data.upload.UploadStatus
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min

interface UploadCoordinator : ObjectUploadEnqueuer {
    suspend fun reconcileUploads()

    suspend fun handleEvent(event: UploadEvent)

    fun heldBackCount(): Int

    fun onObjectUploaded(listener: (String) -> Unit): () -> Unit
}

class UploadCoordinatorDeps(
    val http: SyncHttp,
    val rowStore: RowStorePort,
    val uploader: Uploader,
    val onEntriesChanged: (List<String>) -> Unit,
    val uploadPolicy: UploadPolicy,
    val thumbnails: ThumbnailUploadHook,
    val notificationPrompt: NotificationPromptPort,
    val log: SyncLog,
    val scope: CoroutineScope,
    val ioDispatcher: CoroutineDispatcher,
)

private class ObjectContext(val objectRow: ObjectRow, val entryId: String?)

private class PreparedUpload(val objectId: String, val job: ObjectUploadJob)

private fun partFromNumber(partNumber: Long, url: String, partSizeBytes: Long, sizeBytes: Long): UploadPart {
    val offset = (partNumber - SyncServiceConstants.FIRST_PART_NUMBER) * partSizeBytes
    return UploadPart(partNumber.toInt(), url, offset, min(partSizeBytes, sizeBytes - offset))
}

fun partsFromPresign(response: PresignResponse, sizeBytes: Long): List<UploadPart> = when (response) {
    is PresignResponse.Single -> listOf(UploadPart(SyncServiceConstants.SINGLE_PART_NUMBER, response.url, 0, sizeBytes))
    is PresignResponse.Multipart -> response.partUrls.map { partFromNumber(it.partNumber, it.url, response.partSizeBytes, sizeBytes) }
}

private fun isTransient(error: Throwable): Boolean =
    error is NetworkError ||
        (
            error is ApiError &&
                (error.status >= SyncServiceConstants.STATUS_SERVER_ERROR_MIN || error.status == SyncServiceConstants.STATUS_TOO_MANY_REQUESTS)
            )

class DefaultUploadCoordinator(private val deps: UploadCoordinatorDeps) : UploadCoordinator {
    private val rowStore = deps.rowStore
    private val heldBack: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val uploadedListeners = CopyOnWriteArrayList<(String) -> Unit>()

    override suspend fun enqueueObjectUploads(objectIds: List<String>) {
        val contexts = HashMap<String, ObjectContext>()
        val prepared = mutableListOf<PreparedUpload>()
        for (objectId in objectIds) {
            val context = contextOf(objectId) ?: continue
            val state = context.objectRow.uploadState
            if (state == ObjectUploadState.UPLOADED || state == ObjectUploadState.DEAD) continue
            contexts[objectId] = context
            setUploadState(objectId, ObjectUploadState.QUEUED, context.entryId)
            if (!deps.uploadPolicy.allowed(context.objectRow.sizeBytes)) {
                heldBack += objectId
                continue
            }
            heldBack -= objectId
            prepared += prepare(context)
        }
        submit(prepared, contexts)
    }

    override suspend fun reconcileUploads() {
        val pending = transaction { tx ->
            tx.objects.all().filter { it.uploadState == ObjectUploadState.UPLOADING || it.uploadState == ObjectUploadState.QUEUED }
        }
        if (pending.isEmpty()) return
        val statuses = deps.uploader.statuses()
        for (objectRow in pending) {
            val context = contextOf(objectRow.id) ?: continue
            try {
                reconcileObject(context, statuses[objectRow.id])
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                deps.log.warn(SyncLogEvents.UPLOAD_CONFIRM_FAILED, mapOf(SyncLogFields.OBJECT_ID to objectRow.id))
            }
        }
    }

    override suspend fun handleEvent(event: UploadEvent) {
        val (objectId, reason) = when (event) {
            is UploadEvent.Progress, is UploadEvent.Stopped -> return
            is UploadEvent.Completed -> event.objectId to null
            is UploadEvent.Failed -> event.objectId to event.reason
        }
        val context = contextOf(objectId) ?: return
        when (reason) {
            null -> confirmOrResume(context)
            FailureReason.EXPIRED -> resubmitFresh(context)
            FailureReason.MISSING_FILE -> markDead(context, SyncErrorCodes.MISSING_FILE)
            FailureReason.NETWORK, FailureReason.HTTP -> Unit
        }
    }

    override fun heldBackCount(): Int = heldBack.size

    override fun onObjectUploaded(listener: (String) -> Unit): () -> Unit {
        val entry: (String) -> Unit = { listener(it) }
        uploadedListeners += entry
        return { uploadedListeners.remove(entry) }
    }

    private suspend fun <T> transaction(work: (RowStoreTx) -> T): T = withContext(deps.ioDispatcher) { rowStore.transaction(work) }

    private suspend fun contextOf(objectId: String): ObjectContext? = transaction { tx ->
        val objectRow = tx.objects.get(objectId) ?: return@transaction null
        ObjectContext(objectRow, tx.media.get(objectRow.mediaId)?.entryId)
    }

    private suspend fun setUploadState(objectId: String, state: ObjectUploadState, entryId: String?) {
        val changed = transaction { tx ->
            val objectRow = tx.objects.get(objectId)
            if (objectRow == null || objectRow.uploadState == state || objectRow.uploadState == ObjectUploadState.UPLOADED) return@transaction false
            tx.objects.put(objectRow.copy(uploadState = state))
            true
        }
        if (changed && entryId != null) deps.onEntriesChanged(listOf(entryId))
    }

    private suspend fun prepare(context: ObjectContext): PreparedUpload {
        val objectRow = context.objectRow
        val request = PresignRequest(objectRow.id, objectRow.mediaId, objectRow.variant.toWire(), objectRow.sizeBytes)
        val response = withContext(deps.ioDispatcher) { deps.http.request(SyncCalls.presign(request)) }
        return PreparedUpload(objectRow.id, ObjectUploadJob(objectRow.id, partsFromPresign(response, objectRow.sizeBytes)))
    }

    private fun askForNotifications() {
        deps.scope.launch {
            try {
                deps.notificationPrompt.ensureAsked()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                return@launch
            }
        }
    }

    private suspend fun submit(prepared: List<PreparedUpload>, contexts: Map<String, ObjectContext>) {
        if (prepared.isEmpty()) return
        for (item in prepared) setUploadState(item.objectId, ObjectUploadState.UPLOADING, contexts[item.objectId]?.entryId)
        askForNotifications()
        try {
            deps.uploader.enqueue(prepared.map { it.job })
        } catch (failure: Throwable) {
            for (item in prepared) setUploadState(item.objectId, ObjectUploadState.QUEUED, contexts[item.objectId]?.entryId)
            throw failure
        }
    }

    private suspend fun resubmitFresh(context: ObjectContext) {
        val fresh = prepare(context)
        submit(listOf(fresh), mapOf(context.objectRow.id to context))
    }

    private suspend fun markDead(context: ObjectContext, code: String) {
        val objectRow = context.objectRow
        val entryId = context.entryId
        transaction { tx ->
            tx.objects.put(objectRow.copy(uploadState = ObjectUploadState.DEAD))
            for (row in tx.outbox.all()) {
                val sameEntry = entryIdOfPayload(row.payload) == entryId
                val mediaId = mediaIdOfPayload(row.payload)
                val sameMedia = mediaId == null || mediaId == objectRow.mediaId
                if (entryId != null && sameEntry && sameMedia && isUploadItem(row) && row.state == OutboxState.QUEUED) {
                    tx.outbox.update(row.copy(state = OutboxState.DEAD, lastErrorCode = code))
                }
            }
        }
        if (entryId != null) deps.onEntriesChanged(listOf(entryId))
    }

    private suspend fun resume(context: ObjectContext) {
        val objectRow = context.objectRow
        val missing = try {
            withContext(deps.ioDispatcher) { deps.http.request(SyncCalls.parts(objectRow.id)) }
        } catch (error: ApiError) {
            if (error.status != SyncServiceConstants.STATUS_NOT_FOUND) throw error
            resubmitFresh(context)
            return
        }
        if (missing.partUrls.isEmpty()) return
        val parts = missing.partUrls.map { partFromNumber(it.partNumber, it.url, missing.partSizeBytes, objectRow.sizeBytes) }
        submit(listOf(PreparedUpload(objectRow.id, ObjectUploadJob(objectRow.id, parts))), mapOf(objectRow.id to context))
    }

    private suspend fun confirmOrResume(context: ObjectContext) {
        val objectRow = context.objectRow
        val entryId = context.entryId
        try {
            withContext(deps.ioDispatcher) { deps.http.request(SyncCalls.confirm(objectRow.id)) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            handleConfirmFailure(context, error)
            return
        }
        setUploadState(objectRow.id, ObjectUploadState.UPLOADED, entryId)
        heldBack -= objectRow.id
        if (objectRow.variant == ObjectVariant.THUMBNAIL) deps.thumbnails.afterUpload(objectRow.id)
        if (entryId != null) {
            deps.onEntriesChanged(listOf(entryId))
            uploadedListeners.forEach { it(entryId) }
        }
    }

    private suspend fun handleConfirmFailure(context: ObjectContext, error: Exception) {
        when {
            error is ApiError && error.code == ErrorCode.UPLOAD_INCOMPLETE -> resume(context)
            error is ApiError && error.status == SyncServiceConstants.STATUS_NOT_FOUND -> resubmitFresh(context)
            error is ApiError && error.status == SyncServiceConstants.STATUS_UNAUTHORIZED -> Unit
            isTransient(error) -> deps.log.warn(SyncLogEvents.UPLOAD_CONFIRM_FAILED, mapOf(SyncLogFields.OBJECT_ID to context.objectRow.id))
            error is ApiError -> markDead(context, error.code.wire)
            else -> throw error
        }
    }

    private suspend fun reconcileObject(context: ObjectContext, status: UploadStatus?) {
        if (context.objectRow.uploadState == ObjectUploadState.UPLOADING) {
            if (status == UploadStatus.UPLOADING || status == UploadStatus.QUEUED) return
            confirmOrResume(context)
            return
        }
        enqueueObjectUploads(listOf(context.objectRow.id))
    }
}
