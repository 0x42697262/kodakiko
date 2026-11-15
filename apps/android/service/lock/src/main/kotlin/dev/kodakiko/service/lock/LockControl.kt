package dev.kodakiko.service.lock

import dev.kodakiko.data.secure.DeviceLock
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable

data class KeyCapabilities(val persistent: Boolean, val biometric: Boolean) {
    companion object {
        fun of(lock: DeviceLock): KeyCapabilities =
            KeyCapabilities(persistent = lock != DeviceLock.NONE, biometric = lock == DeviceLock.BIOMETRIC)
    }
}

class LockControlDeps(
    val lockService: LockService,
    val autoLock: AutoLock,
    val accountState: Observable<AccountState>,
    val deviceLock: () -> DeviceLock,
)

class LockControl(private val deps: LockControlDeps) {
    private val guard = Any()
    private var autoLockRunning = false

    val autoLock: AutoLock = deps.autoLock

    val returnTo: Observable<String?> = Observable(null)

    fun capabilities(): KeyCapabilities = KeyCapabilities.of(deps.deviceLock())

    suspend fun lockNow() {
        deps.lockService.lock(LockReason.MANUAL)
    }

    fun lockedInSession(): Boolean = deps.lockService.lockCount() > 0

    fun bind(): () -> Unit {
        val unbindLockService = deps.lockService.bind()
        val unsubscribe = deps.accountState.subscribe { syncAutoLock() }
        syncAutoLock()
        return {
            unsubscribe()
            unbindLockService()
        }
    }

    private fun syncAutoLock() {
        synchronized(guard) {
            val unlocked = deps.accountState.value is AccountState.Unlocked
            if (unlocked && !autoLockRunning) autoLock.start()
            if (!unlocked && autoLockRunning) autoLock.stop()
            autoLockRunning = unlocked
        }
    }
}
