package dev.kodakiko.app.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.BuildConfig
import dev.kodakiko.app.log.AppLogConstants
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.log.DebugLogConstants
import dev.kodakiko.data.log.DebugLogFile
import dev.kodakiko.data.log.DebugLogStore
import dev.kodakiko.data.log.FileDebugLog
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.data.migration.LegacySecureStoreSecrets
import dev.kodakiko.data.migration.SecureStoresTarget
import dev.kodakiko.data.secure.DeviceGate
import dev.kodakiko.data.secure.DeviceLocks
import dev.kodakiko.data.secure.GlobalStore
import dev.kodakiko.data.secure.KeyVault
import dev.kodakiko.data.secure.LegacySecureStore
import dev.kodakiko.data.secure.SessionStore
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.typeOf
import dev.kodakiko.platform.device.ForegroundTracker
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.FileStorePort
import dev.kodakiko.service.account.ForegroundLifecycle
import dev.kodakiko.service.account.GlobalStorePort
import dev.kodakiko.service.account.KeyVaultPort
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.account.SecureGlobalStore
import dev.kodakiko.service.account.SecureKeyVault
import dev.kodakiko.service.account.SecureSessionStore
import dev.kodakiko.service.account.SessionStorePort
import dev.kodakiko.ui.feature.lock.AndroidxPromptLauncher
import dev.kodakiko.ui.feature.lock.BiometricDeviceGate
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

@BindingContainer
object StorageBindings {
    @Provides
    fun context(application: Application): Context = application

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @SingleIn(AppScope::class)
    @ApplicationScope
    fun applicationScope(log: DebugLog): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + uncaught(log))

    internal fun uncaught(log: DebugLog): CoroutineExceptionHandler = CoroutineExceptionHandler { _, failure ->
        log.record(LogLevel.ERROR, LogServices.APP, LogEvents.UNCAUGHT_COROUTINE, mapOf(LogFields.TYPE to typeOf(failure)))
    }

    @Provides
    @SingleIn(AppScope::class)
    fun debugLogFile(context: Context): DebugLogFile = DebugLogFile(
        File(context.filesDir, DebugLogConstants.DIRECTORY),
        defaultOn = BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(AppLogConstants.PREVIEW_SUFFIX),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun fileDebugLog(file: DebugLogFile, @IoDispatcher ioDispatcher: CoroutineDispatcher): FileDebugLog = FileDebugLog(file, ioDispatcher)

    @Provides
    fun debugLog(log: FileDebugLog): DebugLog = log

    @Provides
    fun debugLogStore(log: FileDebugLog): DebugLogStore = log

    @Provides
    @SingleIn(AppScope::class)
    fun sodium(): Sodium = createSodium()

    @Provides
    @SingleIn(AppScope::class)
    fun globalStore(context: Context): GlobalStore = GlobalStore(context)

    @Provides
    @SingleIn(AppScope::class)
    fun globalStorePort(store: GlobalStore): GlobalStorePort = SecureGlobalStore(store)

    @Provides
    @SingleIn(AppScope::class)
    fun sessionStore(context: Context): SessionStore = SessionStore(context)

    @Provides
    @SingleIn(AppScope::class)
    fun sessionStorePort(store: SessionStore, sodium: Sodium): SessionStorePort = SecureSessionStore(store, sodium)

    @Provides
    @SingleIn(AppScope::class)
    fun deviceGate(context: Context, holder: ActivityHolder): DeviceGate =
        BiometricDeviceGate(AndroidxPromptLauncher(context.resources) { holder.current })

    @Provides
    @SingleIn(AppScope::class)
    fun keyVault(context: Context, gate: DeviceGate): KeyVault = KeyVault(context, { DeviceLocks.current(context) }, gate)

    @Provides
    @SingleIn(AppScope::class)
    fun keyVaultPort(vault: KeyVault, sodium: Sodium): KeyVaultPort = SecureKeyVault(vault, sodium)

    @Provides
    @SingleIn(AppScope::class)
    fun rowStore(context: Context, sodium: Sodium): DatabaseRowStore = DatabaseRowStore(context, sodium)

    @Provides
    fun rowStorePort(store: DatabaseRowStore): RowStorePort = store

    @Provides
    @SingleIn(AppScope::class)
    fun fileStore(context: Context, sodium: Sodium): DiskFileStore = DiskFileStore(context, sodium)

    @Provides
    fun fileStorePort(store: DiskFileStore): FileStorePort = store

    @Provides
    @SingleIn(AppScope::class)
    fun lifecycle(): LifecyclePort = ForegroundLifecycle(ForegroundTracker(ProcessLifecycleOwner.get().lifecycle, System::currentTimeMillis))

    @Provides
    @SingleIn(AppScope::class)
    fun legacyAdoption(context: Context, sodium: Sodium, gate: DeviceGate, global: GlobalStore, sessions: SessionStore, vault: KeyVault): LegacyAdoption =
        LegacyAdoption(context, sodium, LegacySecureStoreSecrets(LegacySecureStore(context), gate), SecureStoresTarget(global, sessions, vault))
}
