package dev.kodakiko.app

import dev.kodakiko.app.di.ApplicationScope
import dev.kodakiko.app.di.IoDispatcher
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.migration.LegacyAdoption
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
        scope.launch {
            withContext(ioDispatcher) { tempFiles.wipe() }
            val pending = try {
                withContext(ioDispatcher) { adoption.pendingAccount() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            if (pending != null) accountState.value = AccountState.Locked else restore()
            mutableReady.value = true
        }
    }

    private suspend fun restore() {
        try {
            auth.restore()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            accountState.value = AccountState.NoServer
        }
    }
}
