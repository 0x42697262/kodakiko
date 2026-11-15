package dev.kodakiko.service.lock

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class LockReason(val wire: String) {
    MANUAL("manual"),
    IDLE("idle"),
    BACKGROUND("background"),
    SIGN_OUT("signOut"),
    SESSION_INVALID("sessionInvalid"),
}

class LifecycleStep(val name: String, val run: suspend () -> Unit)

interface LockService {
    suspend fun lock(reason: LockReason)

    suspend fun afterUnlock()

    fun isLocking(): Boolean

    fun bind(): () -> Unit

    suspend fun idle()

    fun lockCount(): Int
}

class LockServiceDeps(
    val lockSteps: List<LifecycleStep>,
    val unlockSteps: List<LifecycleStep>,
    val accountState: Observable<AccountState>,
    val log: (event: String, step: String) -> Unit,
    val scope: CoroutineScope,
    val ioDispatcher: CoroutineDispatcher,
)

class DefaultLockService(private val deps: LockServiceDeps) : LockService {
    private val locking = AtomicBoolean(false)
    private val locks = AtomicInteger(0)

    @Volatile
    private var running: Job = Job().apply { complete() }

    override suspend fun lock(reason: LockReason) {
        if (deps.accountState.value !is AccountState.Unlocked) return
        if (!locking.compareAndSet(false, true)) return
        locks.incrementAndGet()
        startLockSteps { deps.accountState.value = AccountState.Locked }.join()
    }

    override suspend fun afterUnlock() {
        running.join()
        val job = deps.scope.launch(deps.ioDispatcher) { runSteps(deps.unlockSteps, LockLogEvents.UNLOCK_STEP_FAILED) }
        running = job
        job.join()
    }

    override fun isLocking(): Boolean = locking.get()

    override fun bind(): () -> Unit {
        var wasUnlocked = deps.accountState.value is AccountState.Unlocked
        return deps.accountState.subscribe { state ->
            val unlocked = state is AccountState.Unlocked
            if (wasUnlocked && !unlocked && locking.compareAndSet(false, true)) startLockSteps {}
            if (!wasUnlocked && unlocked) deps.scope.launch { afterUnlock() }
            wasUnlocked = unlocked
        }
    }

    override suspend fun idle() {
        running.join()
    }

    override fun lockCount(): Int = locks.get()

    private fun startLockSteps(beforeSteps: () -> Unit): Job {
        try {
            beforeSteps()
        } catch (failure: Throwable) {
            locking.set(false)
            throw failure
        }
        val job = deps.scope.launch(deps.ioDispatcher, start = CoroutineStart.ATOMIC) {
            try {
                withContext(NonCancellable) { runSteps(deps.lockSteps, LockLogEvents.STEP_FAILED) }
            } finally {
                locking.set(false)
            }
        }
        running = job
        return job
    }

    private suspend fun runSteps(steps: List<LifecycleStep>, failureEvent: String) {
        for (step in steps) {
            try {
                step.run()
            } catch (failure: CancellationException) {
                currentCoroutineContext().ensureActive()
                deps.log(failureEvent, step.name)
            } catch (failure: Exception) {
                deps.log(failureEvent, step.name)
            }
        }
    }
}
