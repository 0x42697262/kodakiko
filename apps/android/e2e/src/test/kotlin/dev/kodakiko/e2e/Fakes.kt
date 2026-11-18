package dev.kodakiko.e2e

import android.content.Context
import android.content.ContextWrapper
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.data.net.ConfigCalls
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.upload.PartUploader
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadJob
import dev.kodakiko.data.upload.UploadRegistry
import dev.kodakiko.data.upload.UploadStatus
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.MediaFormat
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.GlobalStorePort
import dev.kodakiko.service.account.KeyVaultPort
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.account.SessionStorePort
import dev.kodakiko.service.account.storeServerConfig
import dev.kodakiko.service.entries.DeviceSettingsSource
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaToolkit
import dev.kodakiko.service.links.PhotoReencoder
import dev.kodakiko.service.links.ReencodedPhoto
import dev.kodakiko.service.sync.ObjectUploadJob
import dev.kodakiko.service.sync.SyncLifecycle
import dev.kodakiko.service.sync.Uploader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

class DeviceContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getFilesDir(): File = File(root, E2eConstants.FILES_DIRECTORY).apply { mkdirs() }

    override fun getCacheDir(): File = File(root, E2eConstants.CACHE_DIRECTORY).apply { mkdirs() }
}

class MemorySessionStore : SessionStorePort {
    private val tokens = HashMap<String, String>()

    @Synchronized
    override fun save(accountKey: String, token: String) {
        tokens[accountKey] = token
    }

    @Synchronized
    override fun load(accountKey: String): String? = tokens[accountKey]

    @Synchronized
    override fun clear(accountKey: String) {
        tokens.remove(accountKey)
    }
}

class MemoryGlobalStore : GlobalStorePort {
    private var serverUrl: String? = null
    private var activeAccount: ActiveAccount? = null
    private var serverConfigJson: String? = null

    @Synchronized
    override fun serverUrl(): String? = serverUrl

    @Synchronized
    override fun setServerUrl(url: String) {
        serverUrl = url
    }

    @Synchronized
    override fun clearServerUrl() {
        serverUrl = null
    }

    @Synchronized
    override fun activeAccount(): ActiveAccount? = activeAccount

    @Synchronized
    override fun setActiveAccount(account: ActiveAccount) {
        activeAccount = account
    }

    @Synchronized
    override fun clearActiveAccount() {
        activeAccount = null
    }

    @Synchronized
    override fun serverConfigJson(): String? = serverConfigJson

    @Synchronized
    override fun setServerConfigJson(json: String) {
        serverConfigJson = json
    }

    @Synchronized
    override fun clearServerConfigJson() {
        serverConfigJson = null
    }
}

class MemoryKeyVault : KeyVaultPort {
    private val keys = HashMap<String, ByteArray>()

    @Synchronized
    override fun storeMasterKey(accountKey: String, masterKey: ByteArray) {
        keys.remove(accountKey)?.let(::wipe)
        keys[accountKey] = masterKey.copyOf()
    }

    @Synchronized
    override fun loadMasterKey(accountKey: String): ByteArray? = keys[accountKey]?.copyOf()

    @Synchronized
    override fun forget(accountKey: String) {
        keys.remove(accountKey)?.let(::wipe)
    }
}

object QuietLifecycle : LifecyclePort, SyncLifecycle {
    override suspend fun afterInteractions() = Unit

    override fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit = {}

    override fun subscribeOnline(listener: () -> Unit): () -> Unit = {}
}

class OnDemandConfigService(
    private val http: HttpClient,
    private val globalStore: GlobalStorePort,
    private val ioDispatcher: CoroutineDispatcher,
) : ConfigService {
    @Volatile
    private var current: PublicConfigDto? = null

    override fun current(): PublicConfigDto? = current

    override suspend fun refresh(): PublicConfigDto? {
        try {
            withContext(ioDispatcher) { accept(http.request(ConfigCalls.get())) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
        }
        return current
    }

    override fun accept(config: PublicConfigDto) {
        current = config
        storeServerConfig(globalStore, config)
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit = {}
}

object TestDeviceSettings : DeviceSettingsSource {
    private val settings: DeviceSettings = DeviceSettingsDefaults.ANDROID.copy(
        encryptThumbnails = true,
        originalCacheCapBytes = 0,
        largeUploadsOnWifiOnly = false,
        largeUploadThresholdBytes = E2eConstants.LARGE_UPLOAD_THRESHOLD_BYTES,
        locationForCaptures = false,
    )

    override fun loaded(): Boolean = true

    override fun get(): DeviceSettings = settings
}

object MemoryMediaToolkit : MediaToolkit {
    override fun byteSource(input: MediaInput): ByteSource = byteSourceFromBytes(input.file.readBytes())

    override fun probe(input: MediaInput, format: MediaFormat): ProbeResult =
        ProbeResult(
            E2eConstants.PROBE_WIDTH,
            E2eConstants.PROBE_HEIGHT,
            if (format.kind == MediaKind.VIDEO) E2eConstants.VIDEO_DURATION_MS else null,
            true,
            0,
        )

    override fun makeThumbnail(input: MediaInput, format: MediaFormat, posterTimeMs: Long): ThumbnailResult =
        ThumbnailResult(decodeBytes(E2eConstants.THUMBNAIL_BASE64URL), E2eConstants.THUMBNAIL_SIDE, E2eConstants.THUMBNAIL_SIDE)

    override fun modifiedUtc(input: MediaInput): String? = null

    override fun discard(input: MediaInput) = Unit
}

object JpegSegmentReencoder : PhotoReencoder {
    override suspend fun reencode(bytes: ByteArray, mimeType: String): ReencodedPhoto {
        if (byteAt(bytes, 0) != E2eConstants.JPEG_PREFIX || byteAt(bytes, 1) != E2eConstants.JPEG_SOI) {
            throw IllegalArgumentException(E2eConstants.NOT_A_JPEG)
        }
        val out = ByteArrayOutputStream()
        out.write(bytes, 0, E2eConstants.JPEG_HEADER_BYTES)
        var position = E2eConstants.JPEG_HEADER_BYTES
        while (position + E2eConstants.JPEG_HEADER_BYTES <= bytes.size) {
            val marker = byteAt(bytes, position + 1)
            if (marker == E2eConstants.JPEG_SOS) {
                out.write(bytes, position, bytes.size - position)
                break
            }
            val lengthAt = position + E2eConstants.JPEG_HEADER_BYTES
            val length = (byteAt(bytes, lengthAt) shl E2eConstants.BYTE_SHIFT) or byteAt(bytes, lengthAt + 1)
            val end = minOf(bytes.size, position + E2eConstants.JPEG_HEADER_BYTES + maxOf(length, E2eConstants.JPEG_LENGTH_BYTES))
            if (marker < E2eConstants.JPEG_APP_FIRST || marker > E2eConstants.JPEG_APP_LAST) out.write(bytes, position, end - position)
            position = end
        }
        return ReencodedPhoto(out.toByteArray(), E2eConstants.PLACEHOLDER_EDGE, E2eConstants.PLACEHOLDER_EDGE)
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int = if (index < bytes.size) bytes[index].toInt() and E2eConstants.BYTE_MASK else 0
}

class PartUploaderAdapter(
    okHttp: OkHttpClient,
    private val fileStore: () -> FileStore,
    private val scope: CoroutineScope,
) : Uploader {
    private val lock = Any()
    private val registry = UploadRegistry()
    private val partUploader = PartUploader(okHttp, registry, onProgress = {})
    private val listeners = CopyOnWriteArraySet<(UploadEvent) -> Unit>()
    private var draining: Job? = null

    init {
        registry.addListener { event -> listeners.forEach { it(event) } }
    }

    override suspend fun enqueue(jobs: List<ObjectUploadJob>) {
        val store = fileStore()
        synchronized(lock) {
            registry.submit(jobs.map { UploadJob(it.objectId, store.paths.objectFile(it.objectId).path, it.parts) })
            if (draining == null) draining = scope.launch { drainUntilEmpty() }
        }
    }

    private suspend fun drainUntilEmpty() {
        while (true) {
            partUploader.drain()
            synchronized(lock) {
                if (!registry.hasPending()) {
                    draining = null
                    return
                }
            }
        }
    }

    override suspend fun statuses(): Map<String, UploadStatus> = registry.snapshot()

    override fun subscribe(listener: (UploadEvent) -> Unit): () -> Unit {
        val entry: (UploadEvent) -> Unit = { listener(it) }
        listeners += entry
        return { listeners.remove(entry) }
    }

    override suspend fun cancelAll() {
        val job = synchronized(lock) {
            val running = draining
            draining = null
            registry.clear()
            running
        }
        job?.cancel()
    }
}
