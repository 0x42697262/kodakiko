package dev.kodakiko.app.gallery

import android.content.Context
import coil3.ImageLoader
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.CiphertextSource
import dev.kodakiko.data.media.DownloadPriority
import dev.kodakiko.data.media.DownloadQueue
import dev.kodakiko.data.media.ImageLoaders
import dev.kodakiko.data.media.MediaUnavailableException
import dev.kodakiko.data.media.OpenedOriginal
import dev.kodakiko.data.media.OriginalCache
import dev.kodakiko.data.media.OriginalHandle
import dev.kodakiko.data.media.OriginalImageRequest
import dev.kodakiko.data.media.StreamFileReader
import dev.kodakiko.data.media.ThumbnailCache
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.entries.EntryKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import dev.kodakiko.app.log.elapsedMs
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import dev.kodakiko.log.NoDebugLog
import dev.kodakiko.log.typeOf

class AccountMediaDeps(
    val context: Context,
    val rowStore: DatabaseRowStore,
    val fileStore: DiskFileStore,
    val sodium: Sodium,
    val entryKeys: EntryKeys,
    val settings: AppDeviceSettings,
    val http: HttpClient,
    val okHttpClient: OkHttpClient,
    val isOffline: () -> Boolean,
    val isWifi: () -> Boolean,
    val localOnly: () -> Boolean,
    val unlocked: () -> Boolean,
    val log: DebugLog = NoDebugLog,
)

class AccountMedia(private val deps: AccountMediaDeps) {
    class Opened(
        val database: KodakikoDatabase,
        val files: FileStore,
        val thumbnails: ThumbnailCache,
        val originals: OriginalCache,
        val downloads: DownloadQueue,
        val loader: ImageLoader,
        val stopModeSwitch: () -> Unit,
    )

    private class KnownFiles(val objectIds: Set<String>, val mediaIds: Set<String>)

    class OpenedReader(val reader: StreamFileReader, val handle: OriginalHandle)

    private val lock = Any()
    private val modeSwitchState = MutableStateFlow(ThumbnailModeSwitch.IDLE)
    private var opened: Opened? = null

    val modeSwitch: StateFlow<ThumbnailModeSwitch> = modeSwitchState.asStateFlow()

    fun current(): Opened? = synchronized(lock) {
        if (!deps.unlocked()) return null
        val database = deps.rowStore.current() ?: return null
        val files = deps.fileStore.current() ?: return null
        opened?.takeIf { it.database === database && it.files === files }?.let { return it }
        release()
        val downloads = DownloadQueue(
            database = database,
            files = files,
            http = deps.http,
            sleep = Thread::sleep,
            now = System::currentTimeMillis,
            isOffline = deps.isOffline,
            okHttpClient = deps.okHttpClient,
        )
        val thumbnails = ThumbnailCache(
            database = database,
            files = files,
            sodium = deps.sodium,
            variantKey = { entryId, mediaId -> runBlocking { deps.entryKeys.variantKey(entryId, mediaId, ObjectVariant.THUMBNAIL) } },
            encryptThumbnails = { deps.settings.settings.value.encryptThumbnails },
            fetch = downloads::fetch,
        )
        val originals = OriginalCache(
            database = database,
            files = files,
            capBytes = { deps.settings.settings.value.originalCacheCapBytes },
            localOnly = deps.localOnly,
            isWifi = deps.isWifi,
            fetch = downloads::fetch,
            now = System::currentTimeMillis,
        )
        val stopModeSwitch = thumbnails.subscribeModeSwitch { modeSwitchState.value = it }
        val loader = ImageLoaders.create(deps.context, thumbnails) { request -> openImage(database, files, originals, request) }
        Opened(database, files, thumbnails, originals, downloads, loader, stopModeSwitch).also { opened = it }
    }

    fun releaseOriginals() {
        synchronized(lock) { opened?.originals?.releaseAll() }
    }

    fun sweepOrphans() {
        val media = current() ?: return
        val known = media.database.transaction { tx -> KnownFiles(tx.objects.all().mapTo(HashSet()) { it.id }, tx.media.all().mapTo(HashSet()) { it.id }) }
        media.files.sweepOrphans(known.objectIds, MediaAdapterConstants.ORPHAN_AGE_MS, known.mediaIds)
    }

    fun enforceCache() {
        current()?.originals?.enforceCap()
    }

    fun abortDownloads() {
        synchronized(lock) { opened?.downloads?.abortAll() }
        deps.log.record(LogLevel.INFO, LogServices.DOWNLOAD, LogEvents.ABORT)
    }

    fun fetchObject(objectId: String) {
        val media = current() ?: throw MediaUnavailableException()
        val started = System.nanoTime()
        try {
            media.downloads.fetch(objectId, DownloadPriority.OPENED)
            deps.log.record(
                LogLevel.INFO,
                LogServices.DOWNLOAD,
                LogEvents.DOWNLOAD,
                mapOf(LogFields.OBJECT_ID to objectId, LogFields.DURATION_MS to elapsedMs(started), LogFields.OUTCOME to LogValues.OK),
            )
        } catch (failure: Throwable) {
            deps.log.record(
                LogLevel.WARN,
                LogServices.DOWNLOAD,
                LogEvents.DOWNLOAD,
                mapOf(LogFields.OBJECT_ID to objectId, LogFields.DURATION_MS to elapsedMs(started), LogFields.OUTCOME to LogValues.FAILED, LogFields.ERROR to typeOf(failure)),
            )
            throw failure
        }
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
        val key = runBlocking { deps.entryKeys.variantKey(entryId, mediaId, ObjectVariant.ORIGINAL) }
        try {
            return StreamFileReader.open(deps.sodium, CiphertextSource.of(files.paths.objectFile(handle.objectId)), key, row.plaintextSize)
        } finally {
            key.fill(0)
        }
    }

    private fun release() {
        val previous = opened ?: return
        opened = null
        previous.downloads.abortAll()
        previous.stopModeSwitch()
        modeSwitchState.value = ThumbnailModeSwitch.IDLE
        previous.thumbnails.clear()
        ImageLoaders.clearMemory(previous.loader)
    }
}
