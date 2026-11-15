package dev.kodakiko.data.media

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class ThumbnailCache(
    private val database: KodakikoDatabase,
    private val files: FileStore,
    private val sodium: Sodium,
    private val variantKey: (entryId: String, mediaId: String) -> ByteArray,
    private val encryptThumbnails: () -> Boolean,
    private val fetch: (objectId: String, priority: DownloadPriority) -> Unit,
) {
    private val memory = LinkedHashMap<String, ThumbnailImage.Bytes>()
    private var generation = 0L
    private val inFlight = HashMap<String, CompletableFuture<Unit>>()
    private val conversionLock = Any()
    private val switchLock = Any()
    private val modeSwitchListeners = CopyOnWriteArrayList<(ThumbnailModeSwitch) -> Unit>()

    @Volatile
    private var currentModeSwitch = ThumbnailModeSwitch.IDLE

    fun get(entryId: String, mediaId: String): ThumbnailImage? {
        local(entryId, mediaId)?.let { return it }
        val row = thumbnailRow(mediaId)
        if (row == null || !downloadable(row)) return null
        try {
            fetchAndSettle(ThumbnailTarget(entryId, row), DownloadPriority.VISIBLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (_: Exception) {
            return null
        }
        return local(entryId, mediaId)
    }

    fun clear() {
        clearMemory()
        synchronized(inFlight) { inFlight.clear() }
    }

    fun afterUpload(objectId: String) {
        synchronized(conversionLock) {
            if (encryptThumbnails()) return
            val row = objectRow(objectId)
            if (row == null || row.localState != ObjectLocalState.PRESENT) return
            if (files.plainThumbnailFile(row.mediaId) == null) {
                val media = database.transaction { it.media.get(row.mediaId) } ?: return
                toPlain(media.entryId, row)
                return
            }
            files.delete(objectId)
            markMissing(objectId)
        }
    }

    fun prefetchAll() {
        val wanted = targets().filter { downloadable(it.row) && files.plainThumbnailFile(it.row.mediaId) == null }
        if (wanted.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(MediaConstants.THUMBNAIL_FETCH_CONCURRENCY, wanted.size)) { task ->
            Thread(task, MediaKeys.THUMBNAIL_PREFETCH_THREAD_NAME).apply { isDaemon = true }
        }
        try {
            wanted.map { target -> pool.submit { prefetchQuietly(target) } }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    fun switchMode(encrypt: Boolean) {
        synchronized(switchLock) {
            try {
                val all = targets()
                val work = if (encrypt) {
                    all.filter { files.plainThumbnailFile(it.row.mediaId) != null }
                } else {
                    all.filter { it.row.localState == ObjectLocalState.PRESENT }
                }
                publish(ThumbnailModeSwitch(running = true, done = 0, total = work.size))
                clearMemory()
                for ((index, target) in work.withIndex()) {
                    try {
                        convert(target, encrypt)
                    } catch (_: Exception) {
                    }
                    publish(ThumbnailModeSwitch(running = true, done = index + 1, total = work.size))
                }
                clearMemory()
                if (encrypt) prefetchAll()
            } finally {
                publish(ThumbnailModeSwitch.IDLE)
            }
        }
    }

    fun modeSwitch(): ThumbnailModeSwitch = currentModeSwitch

    fun subscribeModeSwitch(listener: (ThumbnailModeSwitch) -> Unit): () -> Unit {
        val subscription = { value: ThumbnailModeSwitch -> listener(value) }
        modeSwitchListeners += subscription
        return { modeSwitchListeners -= subscription }
    }

    private fun local(entryId: String, mediaId: String): ThumbnailImage? {
        files.plainThumbnailFile(mediaId)?.let { return ThumbnailImage.PlainFile(it) }
        val started = synchronized(memory) {
            memory.remove(mediaId)?.let { hit ->
                memory[mediaId] = hit
                return hit
            }
            generation
        }
        val row = presentThumbnail(mediaId) ?: return null
        val bytes = decrypt(entryId, row)
        synchronized(memory) {
            val racing = memory[mediaId]
            if (started != generation || racing != null) {
                bytes.fill(0)
                return racing.takeIf { started == generation }
            }
            val image = ThumbnailImage.Bytes(bytes)
            memory[mediaId] = image
            while (memory.size > MediaConstants.THUMBNAIL_MEMORY_CACHE_ITEMS) memory.remove(memory.keys.first())
            return image
        }
    }

    private fun clearMemory() {
        synchronized(memory) {
            generation += 1
            memory.clear()
        }
    }

    private fun decrypt(entryId: String, row: ObjectRow): ByteArray {
        val ciphertext = files.readRange(row.id, 0, Math.toIntExact(row.sizeBytes))
        val key = variantKey(entryId, row.mediaId)
        try {
            return decryptWhole(sodium, key, ciphertext, row.plaintextSize)
        } finally {
            key.fill(0)
        }
    }

    private fun fetchAndSettle(target: ThumbnailTarget, priority: DownloadPriority) {
        val mediaId = target.row.mediaId
        val mine = CompletableFuture<Unit>()
        val existing = synchronized(inFlight) { inFlight.putIfAbsent(mediaId, mine) }
        if (existing != null) return await(existing)
        try {
            if (stillDownloadable(target.row)) {
                fetch(target.row.id, priority)
                settle(target.entryId, target.row.id)
            }
        } catch (failure: Throwable) {
            synchronized(inFlight) { inFlight.remove(mediaId, mine) }
            mine.completeExceptionally(failure)
            throw failure
        }
        synchronized(inFlight) { inFlight.remove(mediaId, mine) }
        mine.complete(Unit)
    }

    private fun await(work: CompletableFuture<Unit>) {
        try {
            work.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun stillDownloadable(row: ObjectRow): Boolean {
        if (files.plainThumbnailFile(row.mediaId) != null) return false
        val current = objectRow(row.id)
        return current != null && downloadable(current)
    }

    private fun settle(entryId: String, objectId: String) {
        synchronized(conversionLock) {
            if (encryptThumbnails()) return
            val row = objectRow(objectId)
            if (row != null && row.localState == ObjectLocalState.PRESENT) toPlain(entryId, row)
        }
    }

    private fun convert(target: ThumbnailTarget, encrypt: Boolean) {
        synchronized(conversionLock) {
            if (encrypt) {
                files.deletePlainThumbnail(target.row.mediaId)
                markMissing(target.row.id)
                return
            }
            val row = objectRow(target.row.id)
            if (row != null && row.localState == ObjectLocalState.PRESENT) toPlain(target.entryId, row)
        }
    }

    private fun toPlain(entryId: String, row: ObjectRow) {
        val plain = decrypt(entryId, row)
        try {
            files.writePlainThumbnail(row.mediaId, plain)
        } finally {
            plain.fill(0)
        }
        files.delete(row.id)
        markMissing(row.id)
    }

    private fun prefetchQuietly(target: ThumbnailTarget) {
        try {
            fetchAndSettle(target, DownloadPriority.BACKGROUND)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
    }

    private fun publish(value: ThumbnailModeSwitch) {
        currentModeSwitch = value
        modeSwitchListeners.forEach { it(value) }
    }

    private fun markMissing(objectId: String) {
        database.transaction { tx ->
            tx.objects.get(objectId)?.let { tx.objects.put(it.copy(localState = ObjectLocalState.MISSING)) }
        }
    }

    private fun downloadable(row: ObjectRow): Boolean =
        row.uploadState == ObjectUploadState.UPLOADED && row.localState == ObjectLocalState.MISSING

    private fun objectRow(objectId: String): ObjectRow? = database.transaction { it.objects.get(objectId) }

    private fun thumbnailRow(mediaId: String): ObjectRow? =
        database.transaction { tx -> tx.objects.byMedia(mediaId).firstOrNull { it.variant == ObjectVariant.THUMBNAIL } }

    private fun presentThumbnail(mediaId: String): ObjectRow? = database.transaction { tx ->
        tx.objects.byMedia(mediaId).firstOrNull { it.variant == ObjectVariant.THUMBNAIL && it.localState == ObjectLocalState.PRESENT }
    }

    private fun targets(): List<ThumbnailTarget> = database.transaction { tx ->
        tx.entries.all().filter { it.trashedAt == null }.flatMap { entry ->
            tx.media.byEntry(entry.id).mapNotNull { media ->
                tx.objects.byMedia(media.id).firstOrNull { it.variant == ObjectVariant.THUMBNAIL }?.let { ThumbnailTarget(entry.id, it) }
            }
        }
    }

    private class ThumbnailTarget(val entryId: String, val row: ObjectRow)
}
