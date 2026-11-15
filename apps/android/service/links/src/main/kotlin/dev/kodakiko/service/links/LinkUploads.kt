package dev.kodakiko.service.links

import dev.kodakiko.contract.ObjectVariant
import dev.kodakiko.contract.PresignRequest
import dev.kodakiko.data.net.SyncCalls
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.service.sync.ObjectUploadJob
import dev.kodakiko.service.sync.SyncHttp
import dev.kodakiko.service.sync.Uploader
import dev.kodakiko.service.sync.partsFromPresign
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

internal class LinkObject(val id: String, val mediaId: String, val variant: ObjectVariant, val sizeBytes: Long)

internal class LinkUploadDeps(val http: SyncHttp, val uploader: Uploader, val files: LinkFiles, val ioDispatcher: CoroutineDispatcher)

internal suspend fun uploadAll(deps: LinkUploadDeps, objects: List<LinkObject>, onBytes: (Long) -> Unit) {
    val pending = objects.mapTo(HashSet()) { it.id }
    val sentByObject = LinkedHashMap<String, Long>()
    val events = Channel<UploadEvent>(Channel.UNLIMITED)
    val stop = deps.uploader.subscribe { event -> events.trySend(event) }
    try {
        val jobs = objects.map { linkObject ->
            val presigned = withContext(deps.ioDispatcher) {
                deps.http.request(SyncCalls.presign(PresignRequest(linkObject.id, linkObject.mediaId, linkObject.variant, linkObject.sizeBytes)))
            }
            ObjectUploadJob(linkObject.id, partsFromPresign(presigned, linkObject.sizeBytes))
        }
        deps.uploader.enqueue(jobs)
        while (pending.isNotEmpty()) {
            when (val event = events.receive()) {
                is UploadEvent.Progress -> if (event.objectId in pending) {
                    sentByObject[event.objectId] = event.sentBytes
                    onBytes(sentByObject.values.sum())
                }
                is UploadEvent.Completed -> pending.remove(event.objectId)
                is UploadEvent.Failed -> if (event.objectId in pending) throw LinkUploadFailedException(event.reason)
                is UploadEvent.Stopped -> Unit
            }
        }
    } finally {
        stop()
        events.close()
    }
    for (linkObject in objects) {
        withContext(deps.ioDispatcher) { deps.http.request(SyncCalls.confirm(linkObject.id)) }
        sentByObject[linkObject.id] = linkObject.sizeBytes
    }
    onBytes(sentByObject.values.sum())
    for (linkObject in objects) withContext(deps.ioDispatcher) { deps.files.delete(linkObject.id) }
}
