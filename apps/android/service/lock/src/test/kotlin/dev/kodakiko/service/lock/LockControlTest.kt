@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.lock

import dev.kodakiko.data.secure.DeviceLock
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LockControlTest {
    private class Setup(
        val accountState: Observable<AccountState>,
        val autoLock: RecordingAutoLock,
        val calls: MutableList<String>,
        val control: LockControl,
    )

    private fun TestScope.setup(initial: AccountState, deviceLock: () -> DeviceLock = { DeviceLock.SECURE }): Setup {
        val accountState = Observable(initial)
        val autoLock = RecordingAutoLock()
        val calls = mutableListOf<String>()
        val service = lockService(accountState, lockSteps = recordingSteps(listOf("stopSync", "wipeKeys"), calls))
        val control = LockControl(
            LockControlDeps(
                lockService = service,
                autoLock = autoLock,
                accountState = accountState,
                deviceLock = deviceLock,
            ),
        )
        return Setup(accountState, autoLock, calls, control)
    }

    @Test
    fun reportsCapabilitiesFromTheBiometricCheck() = runTest {
        var lock = DeviceLock.SECURE
        val subject = setup(unlockedState()) { lock }.control
        assertEquals(KeyCapabilities(persistent = true, biometric = false), subject.capabilities())
        lock = DeviceLock.BIOMETRIC
        assertEquals(KeyCapabilities(persistent = true, biometric = true), subject.capabilities())
    }

    @Test
    fun reportsNotPersistentWhenTheDeviceHasNoSecureScreenLock() = runTest {
        val subject = setup(unlockedState()) { DeviceLock.NONE }.control
        assertEquals(KeyCapabilities(persistent = false, biometric = false), subject.capabilities())
        assertEquals(KeyCapabilities(persistent = false, biometric = false), KeyCapabilities.of(DeviceLock.NONE))
        assertEquals(KeyCapabilities(persistent = true, biometric = false), KeyCapabilities.of(DeviceLock.SECURE))
        assertEquals(KeyCapabilities(persistent = true, biometric = true), KeyCapabilities.of(DeviceLock.BIOMETRIC))
    }

    @Test
    fun lockNowLocksAndMarksTheSessionAsLocked() = runTest {
        val subject = setup(unlockedState())
        assertFalse(subject.control.lockedInSession())

        subject.control.lockNow()

        assertEquals(AccountState.Locked, subject.accountState.value)
        assertEquals(listOf("stopSync", "wipeKeys"), subject.calls)
        assertTrue(subject.control.lockedInSession())
    }

    @Test
    fun lockNowWhileNotUnlockedDoesNothing() = runTest {
        val subject = setup(AccountState.Locked)
        subject.control.lockNow()
        assertEquals(emptyList<String>(), subject.calls)
        assertFalse(subject.control.lockedInSession())
    }

    @Test
    fun bindRunsTheAutoLockOnlyWhileUnlockedAndBindsTheLockService() = runTest {
        val subject = setup(AccountState.Locked)
        val unbind = subject.control.bind()
        assertEquals(0, subject.autoLock.starts)

        subject.accountState.value = unlockedState()
        assertEquals(1, subject.autoLock.starts)
        subject.accountState.value = unlockedState()
        assertEquals(1, subject.autoLock.starts)
        assertEquals(0, subject.autoLock.stops)

        subject.accountState.value = AccountState.SignedOut()
        assertEquals(1, subject.autoLock.stops)
        runCurrent()
        assertEquals(listOf("stopSync", "wipeKeys"), subject.calls)
        assertFalse(subject.control.lockedInSession())

        unbind()
        subject.accountState.value = unlockedState()
        assertEquals(1, subject.autoLock.starts)
    }

    @Test
    fun bindStartsTheAutoLockAtOnceWhenAlreadyUnlocked() = runTest {
        val subject = setup(unlockedState())
        subject.control.bind()
        assertEquals(1, subject.autoLock.starts)
        assertEquals(0, subject.autoLock.stops)
    }

    @Test
    fun exposesTheAutoLockForActivityHoldsAndAnEmptyReturnPath() = runTest {
        val subject = setup(unlockedState())
        assertSame(subject.autoLock, subject.control.autoLock)
        subject.control.autoLock.pause()
        subject.control.autoLock.resume()
        assertEquals(1, subject.autoLock.pauses)
        assertEquals(1, subject.autoLock.resumes)
        assertNull(subject.control.returnTo.value)
    }
}
