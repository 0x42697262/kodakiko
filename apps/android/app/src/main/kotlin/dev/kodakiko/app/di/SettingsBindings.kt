package dev.kodakiko.app.di

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.BuildConfig
import dev.kodakiko.app.R
import dev.kodakiko.app.capture.AppCaptureLocation
import dev.kodakiko.app.edit.ActivityShareLauncher
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.navigation.HomeSettings
import dev.kodakiko.app.settings.AppDebugLogPort
import dev.kodakiko.app.settings.AppSettingsAccount
import dev.kodakiko.app.settings.AppSettingsBackup
import dev.kodakiko.app.settings.AppSettingsCredentials
import dev.kodakiko.app.settings.AppSettingsProfile
import dev.kodakiko.app.settings.AppSettingsStorage
import dev.kodakiko.app.settings.AppSettingsSync
import dev.kodakiko.app.settings.AppSettingsThumbnails
import dev.kodakiko.app.settings.IntentAdminShare
import dev.kodakiko.app.settings.RawLicenses
import dev.kodakiko.app.settings.RestoreSources
import dev.kodakiko.app.settings.SnapshotRestoreService
import dev.kodakiko.app.settings.exportEntryIds
import dev.kodakiko.app.sync.AppSyncState
import dev.kodakiko.app.system.SystemUiSettings
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.log.DebugLogStore
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.DeviceLock
import dev.kodakiko.data.secure.DeviceLocks
import dev.kodakiko.data.secure.KeyVault
import dev.kodakiko.exporting.ZipTimeZone
import dev.kodakiko.log.DebugLog
import dev.kodakiko.service.account.AccountService
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.account.ServerUrlStore
import dev.kodakiko.service.admin.AdminService
import dev.kodakiko.service.admin.DefaultAdminService
import dev.kodakiko.service.backup.DefaultExportService
import dev.kodakiko.service.backup.ExportService
import dev.kodakiko.service.backup.ExportServiceDeps
import dev.kodakiko.service.backup.FileStoreBackupFiles
import dev.kodakiko.service.backup.RestoreService
import dev.kodakiko.service.diagnostics.AssetVectorSource
import dev.kodakiko.service.diagnostics.DefaultDiagnosticsService
import dev.kodakiko.service.diagnostics.DiagnosticsDeps
import dev.kodakiko.service.diagnostics.DiagnosticsService
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.SystemEntriesClock
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.AutoLock
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.sharing.SharingService
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.ui.feature.settings.AboutDeps
import dev.kodakiko.ui.feature.settings.AboutViewModel
import dev.kodakiko.ui.feature.settings.AccountSettingsDeps
import dev.kodakiko.ui.feature.settings.AccountSettingsViewModel
import dev.kodakiko.ui.feature.settings.AdminDeps
import dev.kodakiko.ui.feature.settings.AdminViewModel
import dev.kodakiko.ui.feature.settings.AdvancedSettingsDeps
import dev.kodakiko.ui.feature.settings.AdvancedSettingsViewModel
import dev.kodakiko.ui.feature.settings.AppearanceSettingsViewModel
import dev.kodakiko.ui.feature.settings.CaptureSettingsViewModel
import dev.kodakiko.ui.feature.settings.DebugLogViewModel
import dev.kodakiko.ui.feature.settings.DeleteAccountViewModel
import dev.kodakiko.ui.feature.settings.DiagnosticsViewModel
import dev.kodakiko.ui.feature.settings.ExportViewModel
import dev.kodakiko.ui.feature.settings.GallerySettingsViewModel
import dev.kodakiko.ui.feature.settings.ImportViewModel
import dev.kodakiko.ui.feature.settings.PasswordViewModel
import dev.kodakiko.ui.feature.settings.SecuritySettingsViewModel
import dev.kodakiko.ui.feature.settings.ServerKeysViewModel
import dev.kodakiko.ui.feature.settings.SettingsBiometric
import dev.kodakiko.ui.feature.settings.SettingsDeps
import dev.kodakiko.ui.feature.settings.SettingsHighlight
import dev.kodakiko.ui.feature.settings.SettingsLocation
import dev.kodakiko.ui.feature.settings.SettingsProfile
import dev.kodakiko.ui.feature.settings.SettingsSearchViewModel
import dev.kodakiko.ui.feature.settings.SettingsViewModel
import dev.kodakiko.ui.feature.settings.StorageSettingsViewModel
import dev.kodakiko.ui.feature.settings.SyncSettingsViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.TimeZone

@BindingContainer
object SettingsBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun homeSettings(
        settings: AppDeviceSettings,
        accountMode: Observable<AccountMode>,
        config: ConfigService,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<HomeSettings> {
        val configs = MutableStateFlow(config.current())
        config.subscribe { configs.value = it }
        return combine(settings.settings, accountMode.flow, configs) { current, mode, known -> HomeSettings.of(current, mode, known) }
            .stateIn(scope, SharingStarted.Eagerly, HomeSettings.of(settings.settings.value, accountMode.value, configs.value))
    }

    @Provides
    @SingleIn(AppScope::class)
    fun systemUiSettings(
        settings: AppDeviceSettings,
        accountState: Observable<AccountState>,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<SystemUiSettings> {
        val of = { current: DeviceSettings, state: AccountState -> SystemUiSettings.of(current, settings.loaded(), state is AccountState.Unlocked) }
        return combine(settings.settings, accountState.flow) { current, state -> of(current, state) }.stateIn(scope, SharingStarted.Eagerly, of(settings.settings.value, accountState.value))
    }

    @Provides
    @SingleIn(AppScope::class)
    fun exportService(
        index: IndexService,
        entries: EntriesService,
        entryKeys: EntryKeys,
        rowStore: RowStorePort,
        fileStore: DiskFileStore,
        media: AccountMedia,
        sodium: Sodium,
        autoLock: AutoLock,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ExportService = DefaultExportService(
        ExportServiceDeps(
            entryIds = { exportEntryIds(index) },
            readMetadata = entries::readMetadata,
            entryKeys = entryKeys,
            rowStore = rowStore,
            files = FileStoreBackupFiles(fileStore::require),
            fetchObject = { objectId -> withContext(ioDispatcher) { media.fetchObject(objectId) } },
            enforceCap = { withContext(ioDispatcher) { media.current()?.originals?.enforceCap() } },
            sodium = sodium,
            autoLock = autoLock,
            clock = SystemEntriesClock,
            zipTimeZone = ZipTimeZone { utcMillis -> TimeZone.getDefault().getOffset(utcMillis.toLong()).toDouble() },
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun restoreService(
        sodium: Sodium,
        index: IndexService,
        imports: ImportPipeline,
        entries: EntriesService,
        tempFiles: TempFiles,
        config: ConfigService,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        debugLog: DebugLog,
    ): RestoreService = SnapshotRestoreService(sodium, index, imports, entries, tempFiles, config::current, ioDispatcher, debugLog)

    @Provides
    @SingleIn(AppScope::class)
    fun settingsHighlight(): SettingsHighlight = SettingsHighlight()

    @Provides
    fun settingsDeps(
        context: Context,
        settings: AppDeviceSettings,
        auth: AuthService,
        accounts: AccountService,
        lockControl: LockControl,
        config: ConfigService,
        sync: SyncService,
        syncState: AppSyncState,
        rowStore: RowStorePort,
        media: AccountMedia,
        location: AppCaptureLocation,
        sodium: Sodium,
        exporter: ExportService,
        restorer: RestoreService,
        tempFiles: TempFiles,
        highlight: SettingsHighlight,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        debugLog: DebugLog,
    ): SettingsDeps = SettingsDeps(
        settings = settings,
        account = AppSettingsAccount(auth, accounts, lockControl),
        config = config,
        sync = AppSettingsSync(sync, syncState, rowStore, ioDispatcher),
        storage = AppSettingsStorage(context, media, ioDispatcher),
        thumbnails = AppSettingsThumbnails(media, scope, ioDispatcher),
        biometric = SettingsBiometric { DeviceLocks.current(context) != DeviceLock.NONE },
        location = SettingsLocation { timeoutMs -> location.currentPosition(timeoutMs) != null },
        backup = AppSettingsBackup(context, sodium, exporter, restorer, RestoreSources(context, tempFiles), ioDispatcher, debugLog),
        highlight = highlight,
        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun settingsProfile(
        accounts: AccountService,
        accountState: Observable<AccountState>,
        @ActiveAccountKey activeAccountKey: Observable<String?>,
        serverUrls: ServerUrlStore,
        sharing: SharingService,
        rowStore: RowStorePort,
        keyVault: KeyVault,
        sodium: Sodium,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): SettingsProfile = AppSettingsProfile(accounts, accountState, activeAccountKey, serverUrls, sharing, rowStore, keyVault, sodium, scope, ioDispatcher)

    @Provides
    fun accountSettingsDeps(deps: SettingsDeps, profile: SettingsProfile, auth: AuthService, @ApplicationScope scope: CoroutineScope): AccountSettingsDeps =
        AccountSettingsDeps(settings = deps, profile = profile, credentials = AppSettingsCredentials(auth, scope), nowMs = System::currentTimeMillis)

    @Provides
    @SingleIn(AppScope::class)
    fun diagnosticsService(
        context: Context,
        sodium: Sodium,
        config: ConfigService,
        accountMode: Observable<AccountMode>,
        settings: AppDeviceSettings,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DiagnosticsService = DefaultDiagnosticsService(
        DiagnosticsDeps(
            sodium = sodium,
            vectors = AssetVectorSource(context),
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = Build.VERSION.SDK_INT,
            config = config::current,
            mode = { accountMode.value },
            settings = { settings.settings.value },
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    fun advancedSettingsDeps(
        context: Context,
        deps: SettingsDeps,
        index: IndexService,
        diagnostics: DiagnosticsService,
        debugLogStore: DebugLogStore,
        holder: ActivityHolder,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): AdvancedSettingsDeps = AdvancedSettingsDeps(
        settings = deps,
        index = index,
        diagnostics = diagnostics,
        showDiagnostics = BuildConfig.DEBUG,
        debugLog = AppDebugLogPort(context, debugLogStore, diagnostics, ActivityShareLauncher(context, holder), ioDispatcher),
    )

    @Provides
    fun aboutDeps(context: Context, deps: SettingsDeps, @IoDispatcher ioDispatcher: CoroutineDispatcher): AboutDeps = AboutDeps(
        settings = deps,
        appVersion = BuildConfig.VERSION_NAME,
        licenses = RawLicenses(context, R.raw.open_source_licenses, ioDispatcher),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun adminService(http: HttpClient, @IoDispatcher ioDispatcher: CoroutineDispatcher): AdminService = DefaultAdminService(http, ioDispatcher)

    @Provides
    fun adminDeps(context: Context, holder: ActivityHolder, deps: SettingsDeps, admin: AdminService): AdminDeps = AdminDeps(
        settings = deps,
        admin = admin,
        share = IntentAdminShare(ActivityShareLauncher(context, holder)),
        nowMs = System::currentTimeMillis,
    )

    @Provides
    @IntoMap
    @ViewModelKey(AccountSettingsViewModel::class)
    fun accountSettings(deps: AccountSettingsDeps): ViewModel = AccountSettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(ServerKeysViewModel::class)
    fun serverKeys(deps: AccountSettingsDeps): ViewModel = ServerKeysViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(PasswordViewModel::class)
    fun password(deps: AccountSettingsDeps): ViewModel = PasswordViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(DeleteAccountViewModel::class)
    fun deleteAccount(deps: AccountSettingsDeps): ViewModel = DeleteAccountViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(AdvancedSettingsViewModel::class)
    fun advancedSettings(deps: AdvancedSettingsDeps): ViewModel = AdvancedSettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(DiagnosticsViewModel::class)
    fun diagnostics(deps: AdvancedSettingsDeps): ViewModel = DiagnosticsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(DebugLogViewModel::class)
    fun debugLogViewModel(deps: AdvancedSettingsDeps): ViewModel = DebugLogViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(AboutViewModel::class)
    fun about(deps: AboutDeps): ViewModel = AboutViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(AdminViewModel::class)
    fun admin(deps: AdminDeps): ViewModel = AdminViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(SettingsViewModel::class)
    fun settings(deps: SettingsDeps): ViewModel = SettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(SettingsSearchViewModel::class)
    fun settingsSearch(deps: SettingsDeps): ViewModel = SettingsSearchViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(AppearanceSettingsViewModel::class)
    fun appearanceSettings(deps: SettingsDeps): ViewModel = AppearanceSettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(GallerySettingsViewModel::class)
    fun gallerySettings(deps: SettingsDeps): ViewModel = GallerySettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(CaptureSettingsViewModel::class)
    fun captureSettings(deps: SettingsDeps): ViewModel = CaptureSettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(SyncSettingsViewModel::class)
    fun syncSettings(deps: SettingsDeps): ViewModel = SyncSettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(StorageSettingsViewModel::class)
    fun storageSettings(deps: SettingsDeps): ViewModel = StorageSettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(SecuritySettingsViewModel::class)
    fun securitySettings(deps: SettingsDeps): ViewModel = SecuritySettingsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(ExportViewModel::class)
    fun export(deps: SettingsDeps): ViewModel = ExportViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(ImportViewModel::class)
    fun import(deps: SettingsDeps): ViewModel = ImportViewModel(deps)
}
