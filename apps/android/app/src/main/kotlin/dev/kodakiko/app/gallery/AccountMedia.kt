package dev.kodakiko.app.gallery

import android.content.Context
import coil3.ImageLoader
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.ImageLoaders
import dev.kodakiko.data.media.MediaUnavailableException
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
    private val unlocked: () -> Boolean,
) {
    class Opened(val database: KodakikoDatabase, val files: FileStore, val thumbnails: ThumbnailCache, val loader: ImageLoader)

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
        Opened(database, files, thumbnails, ImageLoaders.create(context, thumbnails) { null }).also { opened = it }
    }

    fun clear() {
        synchronized(lock) { release() }
    }

    private fun release() {
        val previous = opened ?: return
        opened = null
        previous.thumbnails.clear()
        ImageLoaders.clearMemory(previous.loader)
    }
}
