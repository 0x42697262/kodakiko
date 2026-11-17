package dev.kodakiko.app.di

import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.gallery.EntriesChangedBus
import dev.kodakiko.app.sharing.InProcessUploader
import dev.kodakiko.app.sync.AppSyncLifecycle
import dev.kodakiko.app.sync.AppSyncLog
import dev.kodakiko.app.sync.AppSyncState
import dev.kodakiko.app.sync.NetworkWifiSource
import dev.kodakiko.app.sync.SessionInvalidation
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.net.NetworkMonitor
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.sync.ConflictResolverDeps
import dev.kodakiko.service.sync.DefaultConflictResolver
import dev.kodakiko.service.sync.DefaultPull
import dev.kodakiko.service.sync.DefaultSyncService
import dev.kodakiko.service.sync.DefaultUploadCoordinator
import dev.kodakiko.service.sync.NotificationPrompt
import dev.kodakiko.service.sync.NotificationPromptDeps
import dev.kodakiko.service.sync.PullDeps
import dev.kodakiko.service.sync.SyncHttp
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.service.sync.SyncServiceDeps
import dev.kodakiko.service.sync.SystemSyncClock
import dev.kodakiko.service.sync.ThumbnailUploadHook
import dev.kodakiko.service.sync.UploadCoordinatorDeps
import dev.kodakiko.service.sync.WifiSource
import dev.kodakiko.service.sync.WifiUploadPolicy
import dev.kodakiko.service.sync.WifiUploadPolicyDeps
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@BindingContainer
object SyncBindings {
    @Provides
    @SingleIn(AppScope::class)
    @SyncUploads
    fun syncUploader(
        okHttpClient: OkHttpClient,
        fileStore: DiskFileStore,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): InProcessUploader = InProcessUploader(okHttpClient, fileStore::require, scope, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun syncState(@ApplicationScope scope: CoroutineScope): AppSyncState = AppSyncState(scope)

    @Provides
    @SingleIn(AppScope::class)
    fun wifiSource(monitor: NetworkMonitor): WifiSource = NetworkWifiSource(monitor)

    @Provides
    @SingleIn(AppScope::class)
    fun uploadPolicy(wifi: WifiSource, settings: AppDeviceSettings): WifiUploadPolicy =
        WifiUploadPolicy(WifiUploadPolicyDeps(network = wifi, settings = { settings.settings.value }, initialWifi = true))

    @Provides
    @SingleIn(AppScope::class)
    fun syncService(
        http: SyncHttp,
        rowStore: RowStorePort,
        files: EntryFiles,
        @SyncUploads uploader: InProcessUploader,
        policy: WifiUploadPolicy,
        media: AccountMedia,
        sodium: Sodium,
        entryKeys: EntryKeys,
        bus: EntriesChangedBus,
        lifecycle: LifecyclePort,
        monitor: NetworkMonitor,
        accountState: Observable<AccountState>,
        accountMode: Observable<AccountMode>,
        settings: AppDeviceSettings,
        session: SessionInvalidation,
        state: AppSyncState,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        debugLog: DebugLog,
    ): SyncService {
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher + StorageBindings.uncaught(debugLog))
        val syncLog = AppSyncLog(debugLog)
        val unlocked = { accountState.value is AccountState.Unlocked }
        val uploads = DefaultUploadCoordinator(
            UploadCoordinatorDeps(
                http = http,
                rowStore = rowStore,
                uploader = uploader,
                onEntriesChanged = bus::emit,
                uploadPolicy = policy,
                thumbnails = ThumbnailUploadHook { objectId -> withContext(ioDispatcher) { media.current()?.thumbnails?.afterUpload(objectId) } },
                notificationPrompt = NotificationPrompt(NotificationPromptDeps(rowStore = rowStore, request = null, ioDispatcher = ioDispatcher)),
                log = syncLog,
                scope = scope,
                ioDispatcher = ioDispatcher,
            ),
        )
        val service = DefaultSyncService(
            SyncServiceDeps(
                http = http,
                rowStore = rowStore,
                files = files,
                uploader = uploader,
                uploads = uploads,
                pull = DefaultPull(PullDeps(http, rowStore, files, bus::emit, syncLog, ioDispatcher)),
                conflict = DefaultConflictResolver(ConflictResolverDeps(sodium, rowStore, entryKeys, SystemSyncClock, ioDispatcher)),
                onEntriesChanged = bus::emit,
                clock = SystemSyncClock,
                random = Math::random,
                lifecycle = AppSyncLifecycle(lifecycle, monitor),
                accountState = accountState,
                onSessionInvalid = session::invalidate,
                log = syncLog,
                localOnly = { accountMode.value == AccountMode.LOCAL || (settings.loaded() && settings.settings.value.syncPaused) },
                scope = scope,
                ioDispatcher = ioDispatcher,
            ),
        )
        state.publish(service.status())
        service.subscribe(state::publish)
        service.onPassCompleted { report ->
            debugLog.record(
                LogLevel.INFO,
                LogServices.SYNC,
                LogEvents.PASS,
                mapOf(
                    LogFields.PUSHED to report.pushed,
                    LogFields.PULLED_ENTRIES to report.pulledEntries,
                    LogFields.FAILED to report.failed,
                    LogFields.DURATION_MS to report.durationMs,
                ),
            )
            if (unlocked()) {
                scope.launch {
                    attempt {
                        val opened = media.current() ?: return@attempt
                        opened.downloads.resume()
                        opened.thumbnails.prefetchAll()
                    }
                }
            }
        }
        policy.onChange {
            if (unlocked()) {
                service.notifyLocalWrite()
                scope.launch { attempt { media.current()?.originals?.downloadPinned() } }
            }
        }
        accountMode.subscribe { mode -> if (mode == AccountMode.SERVER && unlocked()) service.start() }
        return service
    }
}
