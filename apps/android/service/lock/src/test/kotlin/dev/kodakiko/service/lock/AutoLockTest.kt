@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.lock

import dev.kodakiko.service.account.AccountState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLockTest {
    private val none = emptyList<LockReason>()

    @Test
    fun doesNotLockWhenTheAppReturnsBeforeTheTimeout() = runTest {
        val harness = AutoLockHarness(this)
        harness.setActive(false)
        harness.setSystemTime(START + 5 * MINUTE - SECOND)
        harness.setActive(true)
        assertEquals(none, harness.locks)
        harness.advanceTimersBy(10 * MINUTE)
        assertEquals(none, harness.locks)
    }

    @Test
    fun locksBeforeTheActiveListenerReturnsWhenTheTimeoutHasPassed() = runTest {
        val harness = AutoLockHarness(this)
        harness.setActive(false)
        harness.setSystemTime(START + 5 * MINUTE)
        harness.setActive(true)
        assertEquals(listOf(LockReason.BACKGROUND), harness.locks)
    }

    @Test
    fun locksWhenTheTimerFiresWhileStillInTheBackground() = runTest {
        val harness = AutoLockHarness(this)
        harness.setActive(false)
        harness.advanceTimersBy(5 * MINUTE)
        assertEquals(listOf(LockReason.BACKGROUND), harness.locks)
    }

    @Test
    fun ignoresIdleTimeOnAndroid() = runTest {
        val harness = AutoLockHarness(this)
        harness.advanceTimersBy(60 * MINUTE)
        assertEquals(none, harness.locks)
    }

    @Test
    fun theTimerDoesNotFireBeforeTheTimeout() = runTest {
        val harness = AutoLockHarness(this)
        harness.setActive(false)
        harness.advanceTimersBy(5 * MINUTE - 1)
        assertEquals(none, harness.locks)
        harness.advanceTimersBy(1)
        assertEquals(listOf(LockReason.BACKGROUND), harness.locks)
    }

    @Test
    fun usesTheCurrentAutoLockSetting() = runTest {
        val harness = AutoLockHarness(this, autoLockMinutes = 1)
        harness.setActive(false)
        harness.setSystemTime(START + MINUTE)
        harness.setActive(true)
        assertEquals(listOf(LockReason.BACKGROUND), harness.locks)
    }

    @Test
    fun aPausedAutoLockNeverLocksInTheBackgroundOrOnReturn() = runTest {
        val harness = AutoLockHarness(this)
        harness.autoLock.pause()
        harness.setActive(false)
        harness.advanceTimersBy(30 * MINUTE)
        assertEquals(none, harness.locks)
        harness.setActive(true)
        assertEquals(none, harness.locks)
    }

    @Test
    fun resumeRestartsTheClockForAnAppStillInTheBackground() = runTest {
        val harness = AutoLockHarness(this)
        harness.autoLock.pause()
        harness.setActive(false)
        harness.advanceTimersBy(10 * MINUTE)
        harness.autoLock.resume()
        harness.setSystemTime(START + 15 * MINUTE - SECOND)
        harness.setActive(true)
        assertEquals(none, harness.locks)
    }

    @Test
    fun aResumedAutoLockLocksOnReturnOnceTheTimeoutPassedSinceResume() = runTest {
        val harness = AutoLockHarness(this)
        harness.autoLock.pause()
        harness.setActive(false)
        harness.advanceTimersBy(10 * MINUTE)
        harness.autoLock.resume()
        harness.setSystemTime(START + 15 * MINUTE)
        harness.setActive(true)
        assertEquals(listOf(LockReason.BACKGROUND), harness.locks)
    }

    @Test
    fun doesNotLockWhenTheAccountIsNoLongerUnlocked() = runTest {
        val harness = AutoLockHarness(this)
        harness.setActive(false)
        harness.accountState.value = AccountState.SignedOut()
        harness.advanceTimersBy(5 * MINUTE)
        harness.setSystemTime(START + 20 * MINUTE)
        harness.setActive(true)
        assertEquals(none, harness.locks)
    }

    @Test
    fun startReplacesAnEarlierSubscriptionAndStopRemovesItAndTheTimer() = runTest {
        val lifecycle = TestLifecycle()
        val harness = AutoLockHarness(this, lifecycle = lifecycle)
        assertEquals(1, lifecycle.listenerCount())
        harness.autoLock.start()
        assertEquals(1, lifecycle.listenerCount())
        harness.setActive(false)
        harness.autoLock.stop()
        assertEquals(0, lifecycle.listenerCount())
        harness.advanceTimersBy(5 * MINUTE)
        assertEquals(none, harness.locks)
    }
}
