package dev.kodakiko.service.sync

import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadPart
import dev.kodakiko.data.upload.UploadStatus
import dev.kodakiko.js.JsDate

interface SyncHttp {
    fun <T> request(call: ApiCall<T>): T
}

fun HttpClient.asSyncHttp(): SyncHttp {
    val client = this
    return object : SyncHttp {
        override fun <T> request(call: ApiCall<T>): T = client.request(call)
    }
}

fun interface SyncLog {
    fun warn(event: String, fields: Map<String, Any?>)
}

interface SyncClock {
    fun now(): Long

    fun nowIso(): String
}

object SystemSyncClock : SyncClock {
    override fun now(): Long = System.currentTimeMillis()

    override fun nowIso(): String = JsDate.toIsoString(now().toDouble())
}

interface SyncLifecycle {
    fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit

    fun subscribeOnline(listener: () -> Unit): () -> Unit
}

data class ObjectUploadJob(val objectId: String, val parts: List<UploadPart>)

interface Uploader {
    suspend fun enqueue(jobs: List<ObjectUploadJob>)

    suspend fun statuses(): Map<String, UploadStatus>

    fun subscribe(listener: (UploadEvent) -> Unit): () -> Unit

    suspend fun cancelAll()
}

fun interface UploadPolicy {
    fun allowed(sizeBytes: Long): Boolean
}

fun interface ThumbnailUploadHook {
    suspend fun afterUpload(objectId: String)
}

fun interface NotificationPromptPort {
    suspend fun ensureAsked()
}

interface WifiSource {
    fun isWifi(): Boolean

    fun subscribe(listener: () -> Unit): () -> Unit
}

object AlwaysAllowedUploadPolicy : UploadPolicy {
    override fun allowed(sizeBytes: Long): Boolean = true
}

object NoThumbnailUploadHook : ThumbnailUploadHook {
    override suspend fun afterUpload(objectId: String) = Unit
}
