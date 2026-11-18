@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.lock

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockServiceTest {
    @Test
    fun setsTheStateToLockedFirstRunsEveryStepInOrderEvenWhenSomeThrowAndWipesKeysLast() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val calls = mutableListOf<String>()
        val statesAtStep = mutableListOf<AccountState>()
        val logged = mutableListOf<String>()
        val service = lockService(
            accountState,
            lockSteps = recordingSteps(LOCK_STEP_NAMES, calls, failing = setOf("releaseVideo", "wipeTemp")) {
                statesAtStep.add(accountState.value)
            },
            log = { event, step -> logged.add("$event:$step") },
        )

        service.lock(LockReason.MANUAL)

        assertEquals(LOCK_STEP_NAMES, calls)
        assertEquals("wipeKeys", calls.last())
        assertEquals(setOf<AccountState>(AccountState.Locked), statesAtStep.toSet())
        assertEquals(listOf("lock.step_failed:releaseVideo", "lock.step_failed:wipeTemp"), logged)
        assertEquals(AccountState.Locked, accountState.value)
    }

    @Test
    fun ignoresASecondLockWhileTheFirstIsRunningAndALockWhileAlreadyLocked() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val steps = listOf(LifecycleStep("slow") { gate.await() }) + recordingSteps(listOf("wipeKeys"), calls)
        val service = lockService(accountState, lockSteps = steps)

        val first = launch(start = CoroutineStart.UNDISPATCHED) { service.lock(LockReason.IDLE) }
        assertTrue(service.isLocking())
        assertEquals(AccountState.Locked, accountState.value)
        service.lock(LockReason.MANUAL)
        gate.complete(Unit)
        first.join()
        service.lock(LockReason.MANUAL)

        assertEquals(listOf("wipeKeys"), calls)
        assertFalse(service.isLocking())
    }

    @Test
    fun runsTheLockStepsOnceWhenTheStateLeavesUnlockedSomeOtherWay() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val calls = mutableListOf<String>()
        val service = lockService(accountState, lockSteps = recordingSteps(listOf("stopSync", "wipeKeys"), calls))

        service.bind()
        accountState.value = AccountState.SignedOut()
        service.idle()

        assertEquals(listOf("stopSync", "wipeKeys"), calls)
        assertEquals(AccountState.SignedOut(), accountState.value)
    }

    @Test
    fun afterUnlockRunsTheUnlockStepsInOrderAndAFailureDoesNotStopTheRest() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val calls = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val service = lockService(
            accountState,
            unlockSteps = recordingSteps(UNLOCK_STEP_NAMES, calls, failing = setOf("buildIndex")),
            log = { event, step -> logged.add("$event:$step") },
        )

        service.afterUnlock()

        assertEquals(UNLOCK_STEP_NAMES, calls)
        assertEquals(listOf("unlock.step_failed:buildIndex"), logged)
    }

    @Test
    fun afterUnlockWaitsForARunningLockBeforeTheUnlockSteps() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val service = lockService(
            accountState,
            lockSteps = listOf(LifecycleStep("slow") { gate.await() }) + recordingSteps(listOf("wipeKeys"), calls),
            unlockSteps = recordingSteps(listOf("startSync"), calls),
        )

        val locking = launch(start = CoroutineStart.UNDISPATCHED) { service.lock(LockReason.MANUAL) }
        val unlocking = launch(start = CoroutineStart.UNDISPATCHED) { service.afterUnlock() }
        runCurrent()
        assertEquals(emptyList<String>(), calls)
        gate.complete(Unit)
        joinAll(locking, unlocking)

        assertEquals(listOf("wipeKeys", "startSync"), calls)
    }

    @Test
    fun bindRunsTheUnlockStepsWhenTheStateBecomesUnlockedAndNoLockSteps() = runTest {
        val accountState = Observable<AccountState>(AccountState.Locked)
        val calls = mutableListOf<String>()
        val service = lockService(
            accountState,
            lockSteps = recordingSteps(listOf("wipeKeys"), calls),
            unlockSteps = recordingSteps(UNLOCK_STEP_NAMES, calls),
        )

        service.bind()
        accountState.value = unlockedState()
        runCurrent()

        assertEquals(UNLOCK_STEP_NAMES, calls)
    }

    @Test
    fun bindStopsReactingOnceUnbound() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val calls = mutableListOf<String>()
        val service = lockService(accountState, lockSteps = recordingSteps(listOf("wipeKeys"), calls))

        val unbind = service.bind()
        unbind()
        accountState.value = AccountState.SignedOut()
        runCurrent()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun lockCountCountsOnlyExplicitLocksThatRan() = runTest {
        val accountState = Observable<AccountState>(unlockedState())
        val service = lockService(accountState, lockSteps = recordingSteps(listOf("wipeKeys"), mutableListOf()))
        assertEquals(0, service.lockCount())

        service.lock(LockReason.MANUAL)
        service.lock(LockReason.MANUAL)
        assertEquals(1, service.lockCount())

        service.bind()
        accountState.value = unlockedState()
        runCurrent()
        accountState.value = AccountState.SignedOut()
        service.idle()
        assertEquals(1, service.lockCount())
    }

    @Test
    fun theStepOrderOfTheV031CompositionIsDeclaredForTheAppWiring() {
        assertEquals(
            listOf(
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
                "clearAccount",
                "wipeKeys",
            ),
            LockStepNames.LOCK,
        )
        assertEquals(
            listOf("loadSettings", "startSync", "buildIndex", "sweepOrphans", "enforceCache", "refreshMe"),
            LockStepNames.UNLOCK,
        )
        assertEquals("lock.step_failed", LockLogEvents.STEP_FAILED)
        assertEquals("unlock.step_failed", LockLogEvents.UNLOCK_STEP_FAILED)
    }
}
