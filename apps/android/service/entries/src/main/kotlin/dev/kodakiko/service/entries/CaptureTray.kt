package dev.kodakiko.service.entries

import dev.kodakiko.crypto.wipe
import dev.kodakiko.domain.DeviceLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

class CaptureTrayDeps(
    val files: EntryFiles,
    val isUnlocked: () -> Boolean = { true },
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

class CaptureTray(private val deps: CaptureTrayDeps) {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var currentItems: List<ImportedMedia> = emptyList()

    @Volatile
    private var currentLocation: DeviceLocation? = null

    @Volatile
    private var currentGeneration = 0

    val items: List<ImportedMedia> get() = currentItems

    val deviceLocation: DeviceLocation? get() = currentLocation

    val generation: Int get() = currentGeneration

    suspend fun addImported(item: ImportedMedia, startedGeneration: Int): Boolean {
        val added = synchronized(lock) {
            val accepted = startedGeneration == currentGeneration && deps.isUnlocked()
            if (accepted) currentItems = currentItems + item
            accepted
        }
        if (!added) {
            deleteFiles(item)
            return false
        }
        notifyListeners()
        return true
    }

    fun add(item: ImportedMedia) {
        synchronized(lock) { currentItems = currentItems + item }
        notifyListeners()
    }

    suspend fun remove(mediaId: String) {
        val removed = synchronized(lock) {
            val found = currentItems.firstOrNull { it.mediaId == mediaId }
            if (found != null) currentItems = currentItems.filter { it.mediaId != mediaId }
            found
        } ?: return
        notifyListeners()
        deleteFiles(removed)
    }

    fun reorder(from: Int, to: Int) {
        val moved = synchronized(lock) {
            val items = currentItems
            val item = items.getOrNull(from)
            if (item == null || to < 0 || to >= items.size || from == to) {
                false
            } else {
                currentItems = items.filterIndexed { index, _ -> index != from }.toMutableList().apply { add(to, item) }
                true
            }
        }
        if (moved) notifyListeners()
    }

    fun take(): List<ImportedMedia> {
        val taken = synchronized(lock) { currentItems.also { currentItems = emptyList() } }
        notifyListeners()
        return taken
    }

    internal fun takeIfUnchanged(expectedGeneration: Int, expectedItems: List<ImportedMedia>): List<ImportedMedia>? {
        val taken = synchronized(lock) {
            if (currentGeneration != expectedGeneration || currentItems !== expectedItems) return null
            currentItems.also { currentItems = emptyList() }
        }
        notifyListeners()
        return taken
    }

    suspend fun clear(deleteFiles: Boolean) {
        val cleared = synchronized(lock) {
            currentGeneration += 1
            currentLocation = null
            currentItems.also { currentItems = emptyList() }
        }
        notifyListeners()
        if (deleteFiles) cleared.forEach { deleteFiles(it) }
    }

    fun setDeviceLocation(location: DeviceLocation?) {
        currentLocation = location
        notifyListeners()
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private suspend fun deleteFiles(item: ImportedMedia) {
        withContext(deps.ioDispatcher) { deps.files.deleteMediaFilesQuietly(item.mediaId, item.originalObjectId, item.thumbnailObjectId) }
        wipe(item.mediaKey)
    }
}
