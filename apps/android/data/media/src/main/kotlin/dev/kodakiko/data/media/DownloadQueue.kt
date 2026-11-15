package dev.kodakiko.data.media

import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.net.ObjectCalls
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.EnumMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DownloadQueue(
    private val database: KodakikoDatabase,
    private val files: FileStore,
    private val http: HttpClient,
    private val sleep: (Long) -> Unit,
    private val now: () -> Long,
    private val isOffline: () -> Boolean,
    okHttpClient: OkHttpClient = OkHttpClient(),
) {
    private val bucket: OkHttpClient = okHttpClient.newBuilder()
        .callTimeout(MediaConstants.DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val workers = ThreadPoolExecutor(
        Lane.entries.sumOf { it.limit },
        Lane.entries.sumOf { it.limit },
        MediaConstants.DOWNLOAD_THREAD_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { task -> Thread(task, MediaKeys.DOWNLOAD_THREAD_NAME).apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }
    private val lock = Any()
    private val queued = mutableListOf<DownloadJob>()
    private val running = HashMap<String, DownloadJob>()
    private val jobs = HashMap<String, DownloadJob>()
    private val active = EnumMap<Lane, Int>(Lane::class.java).apply { Lane.entries.forEach { put(it, 0) } }
    private val completedListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val progressListeners = ConcurrentHashMap<String, CopyOnWriteArraySet<(Long, Long) -> Unit>>()

    fun fetch(objectId: String, priority: DownloadPriority) {
        val job = synchronized(lock) {
            val existing = jobs[objectId]
            if (existing != null) {
                if (existing in queued && priority.ordinal < existing.priority.ordinal) {
                    existing.priority = priority
                    pump()
                }
                existing
            } else {
                enqueue(objectId, priority)?.also { pump() }
            }
        } ?: return
        await(job)
    }

    fun cancelBackground() = cancel { it.priority == DownloadPriority.BACKGROUND }

    fun abortAll() = cancel { true }

    fun resume() = pump()

    fun waiting(): Map<String, DownloadPriority> = synchronized(lock) { queued.associate { it.row.id to it.priority } }

    fun onCompleted(listener: (String) -> Unit): () -> Unit {
        completedListeners.add(listener)
        return { completedListeners.remove(listener) }
    }

    fun onProgress(objectId: String, listener: (Long, Long) -> Unit): () -> Unit {
        progressListeners.compute(objectId) { _, current -> (current ?: CopyOnWriteArraySet()).apply { add(listener) } }
        return {
            progressListeners.computeIfPresent(objectId) { _, current -> current.apply { remove(listener) }.takeIf { it.isNotEmpty() } }
        }
    }

    private fun enqueue(objectId: String, priority: DownloadPriority): DownloadJob? {
        val row = database.transaction { it.objects.get(objectId) }
        if (row == null || row.localState == ObjectLocalState.UNAVAILABLE) throw ObjectUnavailableError()
        if (row.localState == ObjectLocalState.PRESENT) return null
        val job = DownloadJob(row, priority)
        queued += job
        jobs[objectId] = job
        return job
    }

    private fun await(job: DownloadJob) {
        try {
            job.result.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun pump() {
        synchronized(lock) {
            queued.sortBy { it.priority.ordinal }
            for (job in queued.toList()) {
                val lane = Lane.of(job.row)
                if (active.getValue(lane) >= lane.limit) continue
                if (job.priority == DownloadPriority.BACKGROUND && isOffline()) continue
                queued.remove(job)
                active[lane] = active.getValue(lane) + 1
                running[job.row.id] = job
                workers.execute { run(job, lane) }
            }
        }
    }

    private fun run(job: DownloadJob, lane: Lane) {
        val outcome = runCatching { download(job) }
        synchronized(lock) {
            active[lane] = active.getValue(lane) - 1
            running.remove(job.row.id, job)
            jobs.remove(job.row.id, job)
            pump()
        }
        outcome.fold(onSuccess = { job.result.complete(Unit) }, onFailure = { job.result.completeExceptionally(it) })
    }

    private fun cancel(predicate: (DownloadJob) -> Boolean) {
        synchronized(lock) {
            for (job in queued.toList()) {
                if (!predicate(job)) continue
                queued.remove(job)
                jobs.remove(job.row.id, job)
                job.result.completeExceptionally(DownloadCancelledError())
            }
            for (job in running.values) if (predicate(job)) job.abort()
        }
    }

    private fun download(job: DownloadJob) {
        var retry = 0
        while (true) {
            try {
                attempt(job)
                break
            } catch (failure: Exception) {
                if (failure is ApiError && failure.status == MediaConstants.HTTP_NOT_FOUND) {
                    updateRow(job.row.id) { it.copy(localState = ObjectLocalState.UNAVAILABLE) }
                    throw ObjectUnavailableError()
                }
                if (job.isAborted) throw DownloadCancelledError()
                val delay = MediaConstants.DOWNLOAD_RETRY_DELAYS_MS.getOrNull(retry)
                if (!retryable(failure) || delay == null) throw failure
                sleep(delay)
                retry += 1
            }
        }
        updateRow(job.row.id) { it.copy(localState = ObjectLocalState.PRESENT, lastAccessAt = now()) }
        for (listener in completedListeners) listener(job.row.id)
    }

    private fun attempt(job: DownloadJob) {
        if (job.isAborted) throw DownloadCancelledError()
        val url = http.request(ObjectCalls.download(job.row.id)).url
        val call = bucket.newCall(Request.Builder().url(url).build())
        job.attach(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw DownloadHttpError(response.code)
                response.body.byteStream().use { stream -> files.writeObject(job.row.id, chunks(job.row, stream)) }
            }
        } catch (failure: IOException) {
            if (call.isCanceled()) job.abort()
            throw failure
        }
    }

    private fun chunks(row: ObjectRow, stream: InputStream): Sequence<ByteArray> = sequence {
        val buffer = ByteArray(MediaConstants.DOWNLOAD_BUFFER_BYTES)
        var received = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read == MediaConstants.END_OF_STREAM) break
            yield(buffer.copyOf(read))
            received += read
            emitProgress(row.id, received, row.sizeBytes)
        }
        if (received != row.sizeBytes) throw DownloadSizeMismatchError(row.sizeBytes, received)
    }

    private fun emitProgress(objectId: String, received: Long, total: Long) {
        progressListeners[objectId]?.forEach { it(received, total) }
    }

    private fun updateRow(objectId: String, change: (ObjectRow) -> ObjectRow) {
        database.transaction { tx -> tx.objects.get(objectId)?.let { tx.objects.put(change(it)) } }
    }

    private fun retryable(failure: Exception): Boolean = when (failure) {
        is DownloadSizeMismatchError -> false
        is ApiError -> failure.status >= MediaConstants.HTTP_SERVER_ERROR_MIN
        else -> true
    }

    private enum class Lane(val limit: Int) {
        THUMBNAIL(MediaConstants.THUMBNAIL_FETCH_CONCURRENCY),
        ORIGINAL(MediaConstants.ORIGINAL_FETCH_CONCURRENCY),
        ;

        companion object {
            fun of(row: ObjectRow): Lane = if (row.variant == ObjectVariant.THUMBNAIL) THUMBNAIL else ORIGINAL
        }
    }

    private class DownloadJob(val row: ObjectRow, var priority: DownloadPriority) {
        val result = CompletableFuture<Unit>()
        private var aborted = false
        private var call: Call? = null

        val isAborted: Boolean get() = synchronized(this) { aborted }

        fun abort() = synchronized(this) {
            aborted = true
            call?.cancel()
        }

        fun attach(next: Call) = synchronized(this) {
            call = next
            if (aborted) next.cancel()
        }
    }
}
