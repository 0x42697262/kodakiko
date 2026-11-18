@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.lock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LockPasswordClearTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()

    private fun TestScope.open(): Pair<LockViewModel, Observed<LockState, LockEvent>> {
        val vm = LockViewModel(
            auth = auth,
            lock = lockControl(0),
            adoption = Fixture.emptyAdoption(),
            pendingChanges = PendingChanges { 0 },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun aSuccessfulPasswordUnlockClearsThePasswordFromTheState() = runTest {
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        vm.onPasswordChange(GOOD_PASSWORD)

        vm.onUnlockWithPassword()

        assertEquals(listOf<Any?>(GOOD_PASSWORD), auth.args("unlockWithPassword"))
        assertEquals("", screen.value.password)
    }

    @Test
    fun aSuccessfulDeviceUnlockClearsATypedPasswordFromTheState() = runTest {
        auth.deviceUnlocks = true
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        vm.onPasswordChange(GOOD_PASSWORD)

        vm.onUnlockWithDevice()

        assertEquals(1, auth.count("unlockWithDevice"))
        assertEquals("", screen.value.password)
    }
}
