package dev.kodakiko.app.gallery

import android.content.Context
import coil3.ImageLoader
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.CiphertextSource
import dev.kodakiko.data.media.ImageLoaders
import dev.kodakiko.data.media.MediaUnavailableException
import dev.kodakiko.data.media.OpenedOriginal
import dev.kodakiko.data.media.OriginalCache
import dev.kodakiko.data.media.OriginalHandle
import dev.kodakiko.data.media.OriginalImageRequest
import dev.kodakiko.data.media.StreamFileReader
import dev.kodakiko.data.media.ThumbnailCache
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.entries.EntryKeys
import kotlinx.coroutines.runBlocking

class AccountMedia(
    private val context: Context,
    private val rowStore: DatabaseRowStore,
    private val fileStore: DiskFileStore,
    private val sodium: Sodium,
    private val entryKeys: EntryKeys,
    private val settings: AppDeviceSettings,
    private val localOnly: () -> Boolean,
    private val unlocked: () -> Boolean,
) {
    class Opened(
        val database: KodakikoDatabase,
        val files: FileStore,
        val thumbnails: ThumbnailCache,
        val originals: OriginalCache,
        val loader: ImageLoader,
    )

    class OpenedReader(val reader: StreamFileReader, val handle: OriginalHandle)

    private val lock = Any()
    private var opened: Opened? = null

    fun current(): Opened? = synchronized(lock) {
        if (!unlocked()) return null
        val database = rowStore.current() ?: return null
        val files = fileStore.current() ?: return null
        opened?.takeIf { it.database === database && it.files === files }?.let { return it }
        release()
        val thumbnails = ThumbnailCache(
            database = database,
            files = files,
            sodium = sodium,
            variantKey = { entryId, mediaId -> runBlocking { entryKeys.variantKey(entryId, mediaId, ObjectVariant.THUMBNAIL) } },
            encryptThumbnails = { settings.settings.value.encryptThumbnails },
            fetch = { _, _ -> throw MediaUnavailableException() },
        )
        val originals = OriginalCache(
            database = database,
            files = files,
            capBytes = { settings.settings.value.originalCacheCapBytes },
            localOnly = localOnly,
            isWifi = { false },
            fetch = { _, _ -> },
            now = System::currentTimeMillis,
        )
        val loader = ImageLoaders.create(context, thumbnails) { request -> openImage(database, files, originals, request) }
        Opened(database, files, thumbnails, originals, loader).also { opened = it }
    }

    fun openOriginal(entryId: String, mediaId: String): OpenedReader? {
        val media = current() ?: throw MediaUnavailableException()
        val handle = media.originals.open(mediaId) ?: return null
        try {
            return OpenedReader(reader(media.database, media.files, handle, entryId, mediaId), handle)
        } catch (failure: Throwable) {
            handle.release()
            throw failure
        }
    }

    fun clear() {
        synchronized(lock) { release() }
    }

    private fun openImage(database: KodakikoDatabase, files: FileStore, originals: OriginalCache, request: OriginalImageRequest): OpenedOriginal? {
        val handle = originals.open(request.mediaId) ?: return null
        try {
            return OpenedOriginal(reader(database, files, handle, request.entryId, request.mediaId), request.mimeType, handle::release)
        } catch (failure: Throwable) {
            handle.release()
            throw failure
        }
    }

    private fun reader(database: KodakikoDatabase, files: FileStore, handle: OriginalHandle, entryId: String, mediaId: String): StreamFileReader {
        val row = database.transaction { it.objects.get(handle.objectId) } ?: throw MediaUnavailableException()
        val key = runBlocking { entryKeys.variantKey(entryId, mediaId, ObjectVariant.ORIGINAL) }
        try {
            return StreamFileReader.open(sodium, CiphertextSource.of(files.paths.objectFile(handle.objectId)), key, row.plaintextSize)
        } finally {
            key.fill(0)
        }
    }

    private fun release() {
        val previous = opened ?: return
        opened = null
        previous.thumbnails.clear()
        ImageLoaders.clearMemory(previous.loader)
    }
}
