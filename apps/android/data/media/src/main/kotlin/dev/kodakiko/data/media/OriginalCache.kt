package dev.kodakiko.data.media

import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.PinRow
import dev.kodakiko.data.files.FileStore
import java.util.concurrent.atomic.AtomicBoolean

class OriginalCache(
    private val database: KodakikoDatabase,
    private val files: FileStore,
    private val capBytes: () -> Long,
    private val localOnly: () -> Boolean,
    private val isWifi: () -> Boolean,
    private val fetch: (objectId: String, priority: DownloadPriority) -> Unit,
    private val now: () -> Long,
) {
    private val openCounts = HashMap<String, Int>()
    private val sweepLock = Any()

    fun retain(objectId: String): () -> Unit {
        synchronized(openCounts) { openCounts[objectId] = (openCounts[objectId] ?: 0) + 1 }
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) release(objectId) }
    }

    fun originalObjectId(mediaId: String): String? = originalRow(mediaId)?.id

    fun enforceCap() {
        val cap = capBytes()
        if (cap == MediaConstants.ORIGINAL_CACHE_NO_LIMIT || localOnly()) return
        synchronized(sweepLock) {
            val present = database.transaction { it.objects.presentOriginalsByAccess() }
            var total = present.sumOf { it.sizeBytes }
            val pinned = pinnedEntryIds()
            for (row in present) {
                if (total <= cap) break
                if (evictIfAllowed(row, pinned)) total -= row.sizeBytes
            }
        }
    }

    fun clearOriginals() {
        if (localOnly()) return
        synchronized(sweepLock) {
            val present = database.transaction { it.objects.presentOriginalsByAccess() }
            val pinned = pinnedEntryIds()
            for (row in present) evictIfAllowed(row, pinned)
        }
    }

    fun usage(): CacheUsage {
        val sizes = files.listSizes()
        val pinned = pinnedEntryIds()
        val rows = database.transaction { tx ->
            val entryByMedia = tx.media.all().associate { it.id to it.entryId }
            tx.objects.all().mapNotNull { row -> entryByMedia[row.mediaId]?.let { row to it } }
        }
        var originalsBytes = 0L
        var thumbnailsBytes = 0L
        var pinnedBytes = 0L
        var pendingUploadBytes = 0L
        for ((row, entryId) in rows) {
            val size = sizes[row.id] ?: 0L
            if (row.uploadState != ObjectUploadState.UPLOADED) pendingUploadBytes += size
            if (row.variant == ObjectVariant.THUMBNAIL) thumbnailsBytes += size else originalsBytes += size
            if (row.variant == ObjectVariant.ORIGINAL && entryId in pinned) pinnedBytes += size
        }
        return CacheUsage(originalsBytes, thumbnailsBytes, pinnedBytes, pendingUploadBytes)
    }

    fun pinEntry(entryId: String) {
        database.transaction { it.pins.put(PinRow(entryId, now())) }
        if (isWifi()) downloadEntry(entryId)
    }

    fun unpinEntry(entryId: String) {
        database.transaction { it.pins.delete(entryId) }
    }

    fun isPinned(entryId: String): Boolean = database.transaction { it.pins.get(entryId) } != null

    fun downloadPinned() {
        if (!isWifi()) return
        for (entryId in pinnedEntryIds()) downloadEntry(entryId)
    }

    private fun release(objectId: String) {
        synchronized(openCounts) {
            val remaining = (openCounts[objectId] ?: 1) - 1
            if (remaining <= 0) openCounts.remove(objectId) else openCounts[objectId] = remaining
        }
    }

    private fun originalRow(mediaId: String): ObjectRow? =
        database.transaction { tx -> tx.objects.byMedia(mediaId).firstOrNull { it.variant == ObjectVariant.ORIGINAL } }

    private fun pinnedEntryIds(): Set<String> = database.transaction { tx -> tx.pins.all().mapTo(HashSet()) { it.entryId } }

    private fun entryOf(row: ObjectRow): String? = database.transaction { it.media.get(row.mediaId) }?.entryId

    private fun evictIfAllowed(row: ObjectRow, pinned: Set<String>): Boolean = synchronized(openCounts) {
        if (!evictable(row, pinned)) return false
        evict(row)
        true
    }

    private fun evictable(row: ObjectRow, pinned: Set<String>): Boolean {
        if (row.uploadState != ObjectUploadState.UPLOADED || row.id in openCounts) return false
        val entryId = entryOf(row)
        return entryId == null || entryId !in pinned
    }

    private fun evict(row: ObjectRow) {
        files.delete(row.id)
        database.transaction { tx ->
            tx.objects.get(row.id)?.let { tx.objects.put(it.copy(localState = ObjectLocalState.MISSING)) }
        }
    }

    private fun entryOriginals(entryId: String): List<ObjectRow> = database.transaction { tx ->
        tx.media.byEntry(entryId).flatMap { media -> tx.objects.byMedia(media.id).filter { it.variant == ObjectVariant.ORIGINAL } }
    }

    private fun downloadEntry(entryId: String) {
        val wanted = entryOriginals(entryId).filter { it.uploadState == ObjectUploadState.UPLOADED && it.localState == ObjectLocalState.MISSING }
        for (row in wanted) {
            try {
                fetch(row.id, DownloadPriority.BACKGROUND)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
            }
        }
    }
}
