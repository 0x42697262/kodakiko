@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.lock

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class UnlockedWorkTest {
    @Test
    fun cancelAndJoinCancelsEveryRunningJobAndWaitsForItsCleanup() = runTest {
        val work = UnlockedWork(StandardTestDispatcher(testScheduler))
        val cleaned = CopyOnWriteArrayList<String>()
        val jobs = listOf("share", "export").map { name ->
            work.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(CLEANUP_MS)
                        cleaned += name
                    }
                }
            }
        }

        work.cancelAndJoin()

        assertEquals(setOf("share", "export"), cleaned.toSet())
        assertTrue(jobs.all { it.isCancelled && it.isCompleted })
    }

    @Test
    fun theScopeTakesNewWorkAfterTheFence() = runTest {
        val work = UnlockedWork(StandardTestDispatcher(testScheduler))
        work.cancelAndJoin()
        var ran = false

        val next = work.scope.launch { ran = true }
        next.join()

        assertTrue(ran)
        assertFalse(next.isCancelled)
    }

    @Test
    fun aLockFencesUnlockedWorkBeforeTheFirstLockStep() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val work = UnlockedWork(StandardTestDispatcher(testScheduler))
        val calls = CopyOnWriteArrayList<String>()
        work.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(CLEANUP_MS)
                    calls += UNLOCKED_JOB
                }
            }
        }
        val service = DefaultLockService(
            LockServiceDeps(
                lockSteps = recordingSteps(LOCK_STEP_NAMES, calls),
                unlockSteps = emptyList(),
                accountState = accountState,
                log = { _, _ -> },
                scope = backgroundScope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                unlockedWork = work,
            ),
        )

        service.lock(LockReason.MANUAL)

        assertEquals(listOf(UNLOCKED_JOB) + LOCK_STEP_NAMES, calls.toList())
    }

    @Test
    fun theStateLeavingUnlockedSomeOtherWayFencesUnlockedWorkToo() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val work = UnlockedWork(StandardTestDispatcher(testScheduler))
        val calls = CopyOnWriteArrayList<String>()
        work.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(CLEANUP_MS)
                    calls += UNLOCKED_JOB
                }
            }
        }
        val service = DefaultLockService(
            LockServiceDeps(
                lockSteps = recordingSteps(listOf("stopSync", "wipeKeys"), calls),
                unlockSteps = emptyList(),
                accountState = accountState,
                log = { _, _ -> },
                scope = backgroundScope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
                unlockedWork = work,
            ),
        )

        service.bind()
        accountState.value = AccountState.SignedOut()
        service.idle()

        assertEquals(listOf(UNLOCKED_JOB, "stopSync", "wipeKeys"), calls.toList())
    }

    private companion object {
        const val CLEANUP_MS = 1_000L
        const val UNLOCKED_JOB = "unlockedJobFinished"
    }
}
