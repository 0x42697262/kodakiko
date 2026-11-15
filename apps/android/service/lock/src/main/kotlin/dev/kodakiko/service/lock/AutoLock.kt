package dev.kodakiko.service.lock

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.account.Observable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

interface AutoLock {
    fun start()

    fun stop()

    fun pause()

    fun resume()
}

class AutoLockDeps(
    val lifecycle: LifecyclePort,
    val clock: WallClock,
    val autoLockMinutes: () -> Int,
    val lock: suspend (LockReason) -> Unit,
    val accountState: Observable<AccountState>,
    val scope: CoroutineScope,
)

class BackgroundAutoLock(private val deps: AutoLockDeps) : AutoLock {
    private val guard = Any()

    @Volatile
    private var paused = false

    @Volatile
    private var resumedAt = 0L

    private var backgroundedAt: Long? = null
    private var timer: Job? = null
    private var unsubscribe: (() -> Unit)? = null

    override fun start() {
        stop()
        synchronized(guard) {
            resumedAt = deps.clock.now()
            unsubscribe = deps.lifecycle.subscribeActive(::onActiveChange)
        }
    }

    override fun stop() {
        val previous = synchronized(guard) {
            clearTimer()
            backgroundedAt = null
            unsubscribe.also { unsubscribe = null }
        }
        previous?.invoke()
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
        resumedAt = deps.clock.now()
    }

    private fun timeoutMs(): Long = deps.autoLockMinutes() * LockConstants.MS_PER_MINUTE

    private fun active(): Boolean = !paused && deps.accountState.value is AccountState.Unlocked

    private fun clearTimer() {
        timer?.cancel()
        timer = null
    }

    private fun lockFor(reason: LockReason) {
        deps.scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { deps.lock(reason) } }
    }

    private fun onActiveChange(isActive: Boolean) {
        if (!isActive) {
            synchronized(guard) {
                backgroundedAt = deps.clock.now()
                clearTimer()
                val timeout = timeoutMs()
                timer = deps.scope.launch {
                    delay(timeout)
                    if (active()) lockFor(LockReason.BACKGROUND)
                }
            }
            return
        }
        val expired = synchronized(guard) {
            clearTimer()
            val since = backgroundedAt
            backgroundedAt = null
            since != null && active() && deps.clock.now() - max(since, resumedAt) >= timeoutMs()
        }
        if (expired) lockFor(LockReason.BACKGROUND)
    }
}
