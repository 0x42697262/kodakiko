package dev.kodakiko.app.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.kodakiko.app.AppConstants
import dev.kodakiko.app.BuildConfig
import dev.kodakiko.app.navigation.AppNavigator
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.net.HttpConstants
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.DeviceLocks
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AccountStorage
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.AuthServiceDeps
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.DefaultAuthService
import dev.kodakiko.service.account.DefaultConfigService
import dev.kodakiko.service.account.FileStorePort
import dev.kodakiko.service.account.GlobalStorePort
import dev.kodakiko.service.account.HttpServerUrlStore
import dev.kodakiko.service.account.KeyVaultPort
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.account.ServerUrlStore
import dev.kodakiko.service.account.SessionStorePort
import dev.kodakiko.service.lock.AutoLock
import dev.kodakiko.service.lock.AutoLockDeps
import dev.kodakiko.service.lock.BackgroundAutoLock
import dev.kodakiko.service.lock.DefaultLockService
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.lock.LockControlDeps
import dev.kodakiko.service.lock.LockReason
import dev.kodakiko.service.lock.LockService
import dev.kodakiko.service.lock.LockServiceDeps
import dev.kodakiko.service.lock.SystemWallClock
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.feature.auth.DeletionDueAt
import dev.kodakiko.ui.feature.lock.PendingChanges
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@BindingContainer
object ServiceBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountState(): Observable<AccountState> = Observable(AccountState.NoServer)

    @Provides
    @SingleIn(AppScope::class)
    @ActiveAccountKey
    fun activeAccountKey(): Observable<String?> = Observable(null)

    @Provides
    @SingleIn(AppScope::class)
    @InviteToken
    fun inviteToken(): Observable<String?> = Observable(null)

    @Provides
    @SingleIn(AppScope::class)
    fun accountMode(): Observable<AccountMode> = Observable(AccountMode.SERVER)

    @Provides
    @SingleIn(AppScope::class)
    fun okHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @SingleIn(AppScope::class)
    fun lockService(
        accountState: Observable<AccountState>,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): LockService = DefaultLockService(
        LockServiceDeps(
            lockSteps = emptyList(),
            unlockSteps = emptyList(),
            accountState = accountState,
            log = { event, step -> Log.w(AppConstants.LOG_TAG, event + AppConstants.LOG_SEPARATOR + step) },
            scope = scope,
            ioDispatcher = ioDispatcher,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun httpClient(
        okHttpClient: OkHttpClient,
        globalStore: GlobalStorePort,
        sessionStore: SessionStorePort,
        @ActiveAccountKey activeAccountKey: Observable<String?>,
        accountState: Observable<AccountState>,
        lockService: LockService,
        @ApplicationScope scope: CoroutineScope,
    ): HttpClient = HttpClient(
        baseUrl = { globalStore.serverUrl().orEmpty() },
        sessionToken = { activeAccountKey.value?.let(sessionStore::load) },
        onSessionInvalid = {
            scope.launch {
                attempt { lockService.lock(LockReason.SESSION_INVALID) }
                accountState.value = AccountState.SignedOut()
            }
        },
        okHttpClient = okHttpClient,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun serverUrlStore(okHttpClient: OkHttpClient, globalStore: GlobalStorePort, @IoDispatcher ioDispatcher: CoroutineDispatcher): ServerUrlStore =
        HttpServerUrlStore(okHttpClient, globalStore, HttpConstants.TIMEOUT_MS, BuildConfig.DEBUG, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun configService(
        http: HttpClient,
        globalStore: GlobalStorePort,
        lifecycle: LifecyclePort,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ConfigService = DefaultConfigService(http, globalStore, lifecycle, scope, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun autoLock(
        lifecycle: LifecyclePort,
        lockService: LockService,
        accountState: Observable<AccountState>,
        @ApplicationScope scope: CoroutineScope,
    ): AutoLock = BackgroundAutoLock(
        AutoLockDeps(
            lifecycle = lifecycle,
            clock = SystemWallClock,
            autoLockMinutes = { DeviceSettingsDefaults.ANDROID.autoLockMinutes },
            lock = { reason -> lockService.lock(reason) },
            accountState = accountState,
            scope = scope,
        ),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun lockControl(context: Context, lockService: LockService, autoLock: AutoLock, accountState: Observable<AccountState>): LockControl =
        LockControl(LockControlDeps(lockService, autoLock, accountState) { DeviceLocks.current(context) })

    @Provides
    @SingleIn(AppScope::class)
    fun appNavigator(accountState: Observable<AccountState>, lockControl: LockControl, rowStore: RowStorePort): AppNavigator =
        AppNavigator(
            accountState = accountState,
            returnTo = lockControl.returnTo,
            entryExists = { id -> rowStore.transaction { it.entries.get(id) } != null },
            startScreen = { DeviceSettingsDefaults.ANDROID.startScreen },
            clock = SystemClock::uptimeMillis,
        )

    @Provides
    @SingleIn(AppScope::class)
    fun authService(
        context: Context,
        sodium: Sodium,
        http: HttpClient,
        serverUrl: ServerUrlStore,
        sessionStore: SessionStorePort,
        keyVault: KeyVaultPort,
        rowStore: RowStorePort,
        fileStore: FileStorePort,
        globalStore: GlobalStorePort,
        lifecycle: LifecyclePort,
        accountState: Observable<AccountState>,
        @ActiveAccountKey activeAccountKey: Observable<String?>,
        config: ConfigService,
        @InviteToken inviteToken: Observable<String?>,
        accountMode: Observable<AccountMode>,
        navigator: AppNavigator,
        lockService: LockService,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): AuthService = DefaultAuthService(
        AuthServiceDeps(
            sodium = sodium,
            http = http,
            serverUrl = serverUrl,
            sessionStore = sessionStore,
            keyVault = keyVault,
            rowStore = rowStore,
            fileStore = fileStore,
            globalStore = globalStore,
            lifecycle = lifecycle,
            nowIso = SystemWallClock::nowIso,
            randomId = { encodeBytes(sodium.randomBytes(AppConstants.RANDOM_ID_BYTES)) },
            accountState = accountState,
            activeAccountKey = activeAccountKey,
            config = config,
            inviteToken = inviteToken,
            deviceLabel = { AppConstants.DEVICE_LABEL },
            homeCollectionName = context.getString(R.string.defaults_home_collection_name),
            showSecuring = navigator::showSecuring,
            dismissSecuring = navigator::dismissSecuring,
            beforeSignOut = { lockService.lock(LockReason.SIGN_OUT) },
            ioDispatcher = ioDispatcher,
            accountMode = accountMode,
        ),
    )

    @Provides
    fun randomSource(sodium: Sodium): RandomSource = RandomSource { length -> sodium.randomBytes(length) }

    @Provides
    fun pendingChanges(rowStore: RowStorePort, @IoDispatcher ioDispatcher: CoroutineDispatcher): PendingChanges = PendingChanges {
        withContext(ioDispatcher) {
            attempt { rowStore.transaction { it.outbox.countByState()[OutboxState.QUEUED]?.toInt() ?: 0 } } ?: 0
        }
    }

    @Provides
    fun deletionDueAt(rowStore: RowStorePort, @IoDispatcher ioDispatcher: CoroutineDispatcher): DeletionDueAt = DeletionDueAt {
        withContext(ioDispatcher) { attempt { AccountStorage.readString(rowStore, StorageKeys.ACCOUNT_DELETION_DUE_AT) } }
    }
}
