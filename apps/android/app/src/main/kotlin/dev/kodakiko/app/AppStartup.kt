package dev.kodakiko.app

import android.os.Build
import dev.kodakiko.app.di.ApplicationScope
import dev.kodakiko.app.di.IoDispatcher
import dev.kodakiko.app.log.elapsedMs
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.LogValues
import dev.kodakiko.log.typeOf
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.LockControl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class AppStartup(
    private val auth: AuthService,
    private val accountState: Observable<AccountState>,
    private val lockControl: LockControl,
    private val adoption: LegacyAdoption,
    private val tempFiles: TempFiles,
    private val log: DebugLog,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutableReady = MutableStateFlow(false)
    private var started = false

    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    fun start() {
        if (started) return
        started = true
        lockControl.bind()
        val started = System.nanoTime()
        log.record(
            LogLevel.INFO,
            LogServices.APP,
            LogEvents.APP_START,
            mapOf(
                LogFields.VERSION to BuildConfig.VERSION_NAME,
                LogFields.VERSION_CODE to BuildConfig.VERSION_CODE,
                LogFields.SDK to Build.VERSION.SDK_INT,
                LogFields.BUILD_TYPE to BuildConfig.BUILD_TYPE,
                LogFields.DEBUGGABLE to BuildConfig.DEBUG,
            ),
        )
        scope.launch {
            accountState.subscribe { state -> log.record(LogLevel.INFO, LogServices.AUTH, LogEvents.STATE, mapOf(LogFields.TO to state::class.simpleName)) }
            val wipeStarted = System.nanoTime()
            withContext(ioDispatcher) { tempFiles.wipe() }
            log.record(LogLevel.INFO, LogServices.APP, LogEvents.TEMP_WIPE, mapOf(LogFields.DURATION_MS to elapsedMs(wipeStarted)))
            val pending = try {
                withContext(ioDispatcher) { adoption.pendingAccount() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            log.record(LogLevel.INFO, LogServices.APP, LogEvents.ADOPTION, mapOf(LogFields.PENDING to (pending != null)))
            if (pending != null) accountState.value = AccountState.Locked else restore()
            log.record(LogLevel.INFO, LogServices.APP, LogEvents.STARTUP_READY, mapOf(LogFields.DURATION_MS to elapsedMs(started)))
            mutableReady.value = true
        }
    }

    private suspend fun restore() {
        try {
            auth.restore()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.record(LogLevel.WARN, LogServices.APP, LogEvents.RESTORE, mapOf(LogFields.OUTCOME to LogValues.FAILED, LogFields.ERROR to typeOf(failure)))
            accountState.value = AccountState.NoServer
        }
    }
}
