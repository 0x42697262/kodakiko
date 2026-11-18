@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.lock

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.LifecyclePort
import dev.kodakiko.service.account.Observable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

const val MINUTE = 60_000L
const val SECOND = 1_000L
const val KEY_LENGTH = 32
const val BACKGROUND_AUTO_LOCK_MINUTES = 5
val START: Long = Instant.parse("2026-06-01T10:00:00.000Z").toEpochMilli()

val LOCK_STEP_NAMES: List<String> = listOf(
    "cancelExport",
    "stopSync",
    "cancelDownloads",
    "releaseVideo",
    "releaseOriginals",
    "clearThumbnails",
    "clearTray",
    "wipeTemp",
    "clearIndex",
    "clearUiState",
    "clearCollectionNames",
    "wipeKeys",
)

val UNLOCK_STEP_NAMES: List<String> = listOf("startSync", "buildIndex", "sweepOrphans", "enforceCache")

fun unlockedState(): AccountState.Unlocked =
    AccountState.Unlocked(
        keys = AccountKeys(
            masterKey = ByteArray(KEY_LENGTH) { 1 },
            privateKey = ByteArray(KEY_LENGTH) { 2 },
            publicKey = ByteArray(KEY_LENGTH) { 3 },
        ),
        userId = "user-1",
        username = "ada",
    )

fun recordingSteps(
    names: List<String>,
    calls: MutableList<String>,
    failing: Set<String> = emptySet(),
    onRun: (String) -> Unit = {},
): List<LifecycleStep> =
    names.map { name ->
        LifecycleStep(name) {
            onRun(name)
            calls.add(name)
            if (name in failing) throw IllegalStateException("$name failed")
        }
    }

fun TestScope.lockService(
    accountState: Observable<AccountState>,
    lockSteps: List<LifecycleStep> = emptyList(),
    unlockSteps: List<LifecycleStep> = emptyList(),
    log: (String, String) -> Unit = { _, _ -> },
): LockService =
    DefaultLockService(
        LockServiceDeps(
            lockSteps = lockSteps,
            unlockSteps = unlockSteps,
            accountState = accountState,
            log = log,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        ),
    )

class TestLifecycle : LifecyclePort {
    private val activeListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun listenerCount(): Int = activeListeners.size

    fun setActive(active: Boolean) {
        activeListeners.toList().forEach { it(active) }
    }

    override suspend fun afterInteractions() = Unit

    override fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit {
        activeListeners.add(listener)
        return { activeListeners.remove(listener) }
    }
}

class AutoLockHarness(
    private val scope: TestScope,
    val lifecycle: LifecyclePort = TestLifecycle(),
    autoLockMinutes: Int = BACKGROUND_AUTO_LOCK_MINUTES,
    start: Boolean = true,
) {
    private var systemOffset = 0L

    val accountState: Observable<AccountState> = Observable(unlockedState())

    val locks: MutableList<LockReason> = mutableListOf()

    val clock: WallClock = object : WallClock {
        override fun now(): Long = START + scope.testScheduler.currentTime + systemOffset

        override fun nowIso(): String = Instant.ofEpochMilli(now()).toString()
    }

    val autoLock: AutoLock = BackgroundAutoLock(
        AutoLockDeps(
            lifecycle = lifecycle,
            clock = clock,
            autoLockMinutes = { autoLockMinutes },
            lock = { reason ->
                locks.add(reason)
                accountState.value = AccountState.Locked
            },
            accountState = accountState,
            scope = scope.backgroundScope,
        ),
    )

    init {
        if (start) autoLock.start()
    }

    fun setActive(active: Boolean) {
        (lifecycle as TestLifecycle).setActive(active)
    }

    fun setSystemTime(at: Long) {
        systemOffset = at - START - scope.testScheduler.currentTime
    }

    fun advanceTimersBy(milliseconds: Long) {
        scope.advanceTimeBy(milliseconds)
        scope.runCurrent()
    }
}

class RecordingAutoLock : AutoLock {
    var starts = 0
        private set
    var stops = 0
        private set
    var pauses = 0
        private set
    var resumes = 0
        private set

    override fun start() {
        starts += 1
    }

    override fun stop() {
        stops += 1
    }

    override fun pause() {
        pauses += 1
    }

    override fun resume() {
        resumes += 1
    }
}
