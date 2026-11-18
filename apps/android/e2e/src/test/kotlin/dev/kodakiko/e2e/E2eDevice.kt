package dev.kodakiko.e2e

import android.content.Context
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.AuthServiceDeps
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.DefaultAuthService
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.HttpServerUrlStore
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.ServerChoice
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.CaptureTrayDeps
import dev.kodakiko.service.entries.DefaultEntriesService
import dev.kodakiko.service.entries.DefaultImportPipeline
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntriesServiceDeps
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeyCache
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.entries.EntryKeysDeps
import dev.kodakiko.service.entries.FileStoreEntryFiles
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.ImportPipelineDeps
import dev.kodakiko.service.entries.SystemEntriesClock
import dev.kodakiko.service.links.DefaultLinkStripper
import dev.kodakiko.service.links.DefaultPublicLinksService
import dev.kodakiko.service.links.FileStoreLinkFiles
import dev.kodakiko.service.links.PublicLinksService
import dev.kodakiko.service.links.PublicLinksServiceDeps
import dev.kodakiko.service.sharing.DefaultSharingService
import dev.kodakiko.service.sharing.SharingAccount
import dev.kodakiko.service.sharing.SharingService
import dev.kodakiko.service.sharing.SharingServiceDeps
import dev.kodakiko.service.sync.AlwaysAllowedUploadPolicy
import dev.kodakiko.service.sync.ConflictResolverDeps
import dev.kodakiko.service.sync.DefaultConflictResolver
import dev.kodakiko.service.sync.DefaultPull
import dev.kodakiko.service.sync.DefaultSyncService
import dev.kodakiko.service.sync.DefaultUploadCoordinator
import dev.kodakiko.service.sync.NoThumbnailUploadHook
import dev.kodakiko.service.sync.NotificationPrompt
import dev.kodakiko.service.sync.NotificationPromptDeps
import dev.kodakiko.service.sync.PullDeps
import dev.kodakiko.service.sync.SyncLog
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.service.sync.SyncServiceDeps
import dev.kodakiko.service.sync.SystemSyncClock
import dev.kodakiko.service.sync.UploadCoordinatorDeps
import dev.kodakiko.service.sync.asSyncHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class Account(val username: String, val password: String)

data class LogRecord(val event: String, val fields: Map<String, Any?>)

class E2eDevice(val name: String, base: Context, index: Int, private val server: E2eServer, val account: Account) {
    val sodium = createSodium()
    val context: Context = DeviceContext(base, File(base.filesDir, E2eConstants.DEVICE_DIRECTORY_PREFIX + index))
    val fetcher = TestFetcher()
    val okHttp: OkHttpClient = OkHttpClient.Builder().addInterceptor(fetcher).build()
    val io = Dispatchers.IO
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val randomId: () -> String = { encodeBytes(sodium.randomBytes(E2eConstants.ID_BYTES)) }
    val logs: MutableList<LogRecord> = CopyOnWriteArrayList()
    private val entriesChangedListeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    val rowStore = DatabaseRowStore(context, sodium)
    val fileStore = DiskFileStore(context, sodium)
    val sessionStore = MemorySessionStore()
    val globalStore = MemoryGlobalStore()
    val keyVault = MemoryKeyVault()
    val accountState: Observable<AccountState> = Observable(AccountState.NoServer)
    val activeAccountKey: Observable<String?> = Observable(null)
    val accountMode: Observable<AccountMode> = Observable(AccountMode.SERVER)

    val http = HttpClient(
        baseUrl = { server.baseUrl },
        sessionToken = { activeAccountKey.value?.let(sessionStore::load) },
        onSessionInvalid = { accountState.value = AccountState.SignedOut() },
        okHttpClient = okHttp,
    )
    private val syncHttp = http.asSyncHttp()
    val config = OnDemandConfigService(http, globalStore, io)
    private val log = SyncLog { event, fields -> logs += LogRecord(event, fields.toMap()) }

    val auth: AuthService = DefaultAuthService(
        AuthServiceDeps(
            sodium = sodium,
            http = http,
            serverUrl = HttpServerUrlStore(okHttp, globalStore, E2eConstants.SERVER_URL_TIMEOUT_MS, true, io),
            sessionStore = sessionStore,
            keyVault = keyVault,
            rowStore = rowStore,
            fileStore = fileStore,
            globalStore = globalStore,
            lifecycle = QuietLifecycle,
            nowIso = SystemSyncClock::nowIso,
            randomId = randomId,
            accountState = accountState,
            activeAccountKey = activeAccountKey,
            config = config,
            inviteToken = Observable(null),
            deviceLabel = { E2eConstants.DEVICE_LABEL },
            homeCollectionName = E2eConstants.HOME_COLLECTION_NAME,
            showSecuring = {},
            dismissSecuring = {},
            ioDispatcher = io,
            accountMode = accountMode,
        ),
    )

    val files: EntryFiles = FileStoreEntryFiles { fileStore.require() }

    val entryKeys: EntryKeys = EntryKeyCache(
        EntryKeysDeps(sodium = sodium, rowStore = rowStore, account = { (accountState.value as? AccountState.Unlocked)?.keys }, ioDispatcher = io),
    )

    val uploader = PartUploaderAdapter(okHttp, { fileStore.require() }, scope)

    private val uploads = DefaultUploadCoordinator(
        UploadCoordinatorDeps(
            http = syncHttp,
            rowStore = rowStore,
            uploader = uploader,
            onEntriesChanged = ::emitEntriesChanged,
            uploadPolicy = AlwaysAllowedUploadPolicy,
            thumbnails = NoThumbnailUploadHook,
            notificationPrompt = NotificationPrompt(NotificationPromptDeps(rowStore, null, io)),
            log = log,
            scope = scope,
            ioDispatcher = io,
        ),
    )

    val sync: SyncService = DefaultSyncService(
        SyncServiceDeps(
            http = syncHttp,
            rowStore = rowStore,
            files = files,
            uploader = uploader,
            uploads = uploads,
            pull = DefaultPull(PullDeps(syncHttp, rowStore, files, ::emitEntriesChanged, log, io)),
            conflict = DefaultConflictResolver(ConflictResolverDeps(sodium, rowStore, entryKeys, SystemSyncClock, io)),
            onEntriesChanged = ::emitEntriesChanged,
            clock = SystemSyncClock,
            random = Math::random,
            lifecycle = QuietLifecycle,
            accountState = accountState,
            onSessionInvalid = { accountState.value = AccountState.SignedOut() },
            log = log,
            localOnly = { auth.mode.value == AccountMode.LOCAL },
            scope = scope,
            ioDispatcher = io,
        ),
    )

    val captureTray = CaptureTray(CaptureTrayDeps(files = files, isUnlocked = { accountState.value is AccountState.Unlocked }, ioDispatcher = io))

    val mediaImport: ImportPipeline = DefaultImportPipeline(
        ImportPipelineDeps(
            sodium = sodium,
            files = files,
            toolkit = MemoryMediaToolkit,
            clock = SystemEntriesClock,
            config = config::current,
            deviceSettings = TestDeviceSettings,
            randomId = randomId,
            ioDispatcher = io,
        ),
    )

    val entries: EntriesService = DefaultEntriesService(
        EntriesServiceDeps(
            sodium = sodium,
            rowStore = rowStore,
            files = files,
            entryKeys = entryKeys,
            captureTray = captureTray,
            config = config::current,
            sync = sync,
            onEntriesChanged = ::emitEntriesChanged,
            clock = SystemEntriesClock,
            randomId = randomId,
            userId = { (accountState.value as? AccountState.Unlocked)?.userId },
            ioDispatcher = io,
        ),
    )

    val sharing: SharingService = DefaultSharingService(
        SharingServiceDeps(
            sodium = sodium,
            http = syncHttp,
            rowStore = rowStore,
            files = files,
            entryKeys = entryKeys,
            account = { (accountState.value as? AccountState.Unlocked)?.let { SharingAccount(it.userId, it.keys) } },
            sharingEnabled = { config.current()?.sharingEnabled == true },
            randomId = randomId,
            clock = SystemSyncClock,
            afterChange = { ids -> if (ids.isNotEmpty()) emitEntriesChanged(ids) },
            ioDispatcher = io,
        ),
    )

    val links: PublicLinksService = DefaultPublicLinksService(
        PublicLinksServiceDeps(
            http = syncHttp,
            sodium = sodium,
            account = { (accountState.value as? AccountState.Unlocked)?.keys },
            readMetadata = entries::readMetadata,
            entryKeys = entryKeys,
            rowStore = rowStore,
            files = FileStoreLinkFiles { fileStore.require() },
            fetchObject = {},
            stripper = DefaultLinkStripper(JpegSegmentReencoder),
            uploader = uploader,
            baseUrl = { server.baseUrl },
            randomId = randomId,
            clock = SystemSyncClock,
            ioDispatcher = io,
        ),
    )

    var userId: String = ""
        private set

    var recoveryWords: List<String> = emptyList()

    init {
        var wasUnlocked = false
        accountState.subscribe { next ->
            val unlocked = next is AccountState.Unlocked
            if (wasUnlocked && !unlocked) {
                sync.stop()
                entryKeys.clear()
                scope.launch { clearTrayQuietly() }
            }
            wasUnlocked = unlocked
        }
    }

    private suspend fun clearTrayQuietly() {
        try {
            captureTray.clear(true)
        } catch (_: IllegalStateException) {
            return
        }
    }

    fun onEntriesChanged(listener: (List<String>) -> Unit): () -> Unit {
        entriesChangedListeners += listener
        return { entriesChangedListeners.remove(listener) }
    }

    private fun emitEntriesChanged(ids: List<String>) {
        entriesChangedListeners.forEach { it(ids) }
    }

    fun unlocked(): AccountState.Unlocked = accountState.value as? AccountState.Unlocked ?: error("$name is not unlocked")

    fun rememberUserId() {
        userId = unlocked().userId
    }

    suspend fun chooseServer() {
        val choice = auth.chooseServer(server.baseUrl)
        check(choice is ServerChoice.Ok) { "$name cannot reach the e2e server: $choice" }
    }

    fun close() {
        sync.stop()
        scope.cancel()
        rowStore.close()
    }
}
