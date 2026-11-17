@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.lock

import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.text
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class LockViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private var pending = 0

    private fun TestScope.open(
        locksInSession: Int = 0,
        adoption: LegacyAdoption = Fixture.emptyAdoption(),
        lock: LockControl = lockControl(locksInSession),
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): Pair<LockViewModel, Observed<LockState, LockEvent>> {
        val vm = LockViewModel(auth = auth, lock = lock, adoption = adoption, pendingChanges = PendingChanges { pending }, ioDispatcher = io)
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun showsTheLockedUsernameWithThePasswordFieldHidden() = runTest {
        val (_, screen) = open()
        assertEquals("alice", screen.value.username)
        assertFalse(screen.value.passwordShown)
        assertFalse(screen.value.updatedNotice)
        assertFalse(screen.value.unreadable)
        assertNull(screen.value.error)
        assertNull(screen.value.dialog)
    }

    @Test
    fun withoutAnActiveAccountThereIsNoUsername() = runTest {
        auth.username = null
        val (_, screen) = open()
        assertNull(screen.value.username)
    }

    @Test
    fun aColdStartDoesNotPromptBecauseRestoreAlreadyTriedTheDevice() = runTest {
        open(locksInSession = 0)
        assertEquals(0, auth.count("unlockWithDevice"))
        assertEquals(0, auth.count("restore"))
    }

    @Test
    fun aLockDuringTheSessionPromptsOnceByItself() = runTest {
        auth.deviceUnlocks = true
        val (_, screen) = open(locksInSession = 1)
        assertEquals(1, auth.count("unlockWithDevice"))
        assertFalse(screen.value.passwordShown)
    }

    @Test
    fun aCancelledPromptFallsBackToThePasswordWithoutPromptingAgain() = runTest {
        auth.deviceUnlocks = false
        val (_, screen) = open(locksInSession = 1)
        assertTrue(screen.value.passwordShown)
        assertFalse(screen.value.updatedNotice)
        assertEquals(1, auth.count("unlockWithDevice"))
    }

    @Test
    fun aFailingPromptFallsBackToThePassword() = runTest {
        auth.failures["unlockWithDevice"] = IllegalStateException("keystore")
        val (_, screen) = open(locksInSession = 2)
        assertTrue(screen.value.passwordShown)
        assertNull(screen.value.error)
    }

    @Test
    fun unlockAsksTheDeviceAgainAndFallsBackToThePassword() = runTest {
        val (vm, screen) = open()
        vm.onUnlockWithDevice()
        assertEquals(1, auth.count("unlockWithDevice"))
        assertTrue(screen.value.passwordShown)
    }

    @Test
    fun unlockIsIgnoredWhileThePromptIsOpen() = runTest {
        val gate = auth.hold("unlockWithDevice")
        val (vm, screen) = open()
        vm.onUnlockWithDevice()
        assertTrue(screen.value.busy)
        vm.onUnlockWithDevice()
        assertEquals(1, auth.count("unlockWithDevice"))
        auth.deviceUnlocks = true
        gate.complete(Unit)
        assertFalse(screen.value.busy)
        assertFalse(screen.value.passwordShown)
    }

    @Test
    fun unlockWithPasswordFirstShowsTheFieldThenUnlocksWithWhatWasTyped() = runTest {
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        assertTrue(screen.value.passwordShown)
        assertEquals(0, auth.count("unlockWithPassword"))
        vm.onPasswordChange(GOOD_PASSWORD)
        assertEquals(GOOD_PASSWORD, screen.value.password)
        vm.onUnlockWithPassword()
        assertEquals(listOf<Any?>(GOOD_PASSWORD), auth.args("unlockWithPassword"))
        assertNull(screen.value.error)
        assertFalse(screen.value.wrongPassword)
    }

    @Test
    fun aWrongPasswordIsShownOnTheFieldAndClearsIt() = runTest {
        auth.failures["unlockWithPassword"] = AuthError(AuthFailure.WRONG_PASSWORD)
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        vm.onPasswordChange("not it at all")
        vm.onUnlockWithPassword()
        assertTrue(screen.value.wrongPassword)
        assertEquals("", screen.value.password)
        assertNull(screen.value.error)
        assertTrue(screen.value.passwordShown)
    }

    @Test
    fun otherUnlockFailuresShowABannerAndClearThePassword() = runTest {
        auth.failures["unlockWithPassword"] = AuthError(AuthFailure.INVALID_CREDENTIALS)
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onUnlockWithPassword()
        assertFalse(screen.value.wrongPassword)
        assertEquals(TestTags.Errors.INVALID_CREDENTIALS, screen.value.error?.testTag)
        assertEquals("Username or password is incorrect.", screen.value.error!!.text(resources))
        assertEquals("", screen.value.password)
    }

    @Test
    fun anUnexpectedUnlockFailureShowsTheGenericBanner() = runTest {
        auth.failures["unlockWithPassword"] = IllegalStateException("bug")
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        vm.onUnlockWithPassword()
        assertEquals(TestTags.Errors.GENERIC, screen.value.error?.testTag)
    }

    @Test
    fun aNewAttemptClearsTheOldWrongPassword() = runTest {
        auth.failures["unlockWithPassword"] = AuthError(AuthFailure.WRONG_PASSWORD)
        val (vm, screen) = open()
        vm.onUnlockWithPassword()
        vm.onUnlockWithPassword()
        assertTrue(screen.value.wrongPassword)
        auth.failures.remove("unlockWithPassword")
        val gate = auth.hold("unlockWithPassword")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onUnlockWithPassword()
        assertFalse(screen.value.wrongPassword)
        assertTrue(screen.value.busy)
        vm.onUnlockWithPassword()
        assertEquals(2, auth.count("unlockWithPassword"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
    }

    @Test
    fun forgotOpensRecovery() = runTest {
        val (vm, screen) = open()
        vm.onForgot()
        assertEquals(listOf(LockEvent.Push(Route.Recover)), screen.events)
    }

    @Test
    fun signOutWithoutWaitingChangesSignsOutAtOnce() = runTest {
        val (vm, screen) = open()
        vm.onSignOut()
        assertEquals(1, auth.count("signOut"))
        assertNull(screen.value.dialog)
    }

    @Test
    fun signOutWithWaitingChangesAsksFirst() = runTest {
        pending = 3
        val (vm, screen) = open()
        vm.onSignOut()
        assertEquals(LockDialog.SignOut, screen.value.dialog)
        assertEquals(0, auth.count("signOut"))
        vm.onDismissDialog()
        assertNull(screen.value.dialog)
        assertEquals(0, auth.count("signOut"))
        vm.onSignOut()
        vm.onSignOutConfirm()
        assertNull(screen.value.dialog)
        assertEquals(1, auth.count("signOut"))
    }

    @Test
    fun aFailingSignOutIsSwallowed() = runTest {
        auth.failures["signOut"] = IllegalStateException("offline")
        val (vm, screen) = open()
        vm.onSignOut()
        assertEquals(1, auth.count("signOut"))
        assertNull(screen.value.error)
    }

    @Test
    fun withoutALegacyInstallNoAdoptionRuns() = runTest {
        val legacy = FakeLegacy()
        val target = FakeTarget()
        open(locksInSession = 0, adoption = Fixture.adoption(legacy, target))
        assertEquals(0, target.calls)
        assertEquals(0, legacy.forgotten)
        assertEquals(0, auth.count("restore"))
    }

    @Test
    fun theFirstUnlockAfterTheUpdateAdoptsOffTheMainThreadThenRestores() = runTest {
        Fixture.install()
        val legacy = Fixture.legacy()
        val target = FakeTarget()
        val io = StandardTestDispatcher(testScheduler)
        val (_, screen) = open(adoption = Fixture.adoption(legacy, target), io = io)
        assertEquals(0, target.calls)
        assertEquals(0, auth.count("restore"))
        advanceUntilIdle()
        assertTrue(target.calls > 0)
        assertEquals(1, legacy.forgotten)
        assertEquals(1, auth.count("restore"))
        assertEquals(FIXTURE_USERNAME, screen.value.username)
    }

    @Test
    fun aCarriedMasterKeyUnlocksWithoutThePasswordOrTheUpdatedNotice() = runTest {
        Fixture.install()
        val masterKey = Fixture.masterKey()
        val target = FakeTarget()
        val (_, screen) = open(adoption = Fixture.adoption(Fixture.legacy(masterKey), target))
        assertTrue(target.masterKeys.containsKey(FIXTURE_HASH))
        assertEquals(1, auth.count("restore"))
        assertEquals(0, auth.count("unlockWithDevice"))
        assertFalse(screen.value.passwordShown)
        assertFalse(screen.value.updatedNotice)
        assertFalse(screen.value.unreadable)
    }

    @Test
    fun withoutAReadableMasterKeyItAsksForThePasswordOnceWithTheUpdatedNotice() = runTest {
        Fixture.install()
        val (vm, screen) = open(adoption = Fixture.adoption(Fixture.legacy(), FakeTarget()))
        assertEquals(1, auth.count("restore"))
        assertEquals(0, auth.count("unlockWithDevice"))
        assertTrue(screen.value.passwordShown)
        assertTrue(screen.value.updatedNotice)
        assertEquals(FIXTURE_USERNAME, screen.value.username)
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onUnlockWithPassword()
        assertEquals(listOf<Any?>(GOOD_PASSWORD), auth.args("unlockWithPassword"))
    }

    @Test
    fun aPasswordSubmittedWhileTheAdoptionIsBusyIsKeptAndUnlocksOnceTheAdoptionEnds() = runTest {
        Fixture.install()
        val queued = ArrayDeque<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.addLast(block)
            }
        }
        val (vm, screen) = open(adoption = Fixture.adoption(Fixture.legacy(), FakeTarget()), io = io)
        queued.removeFirst().run()
        assertTrue(screen.value.busy)
        vm.onUnlockWithPassword()
        assertTrue(screen.value.passwordShown)
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onUnlockWithPassword()
        vm.onUnlockWithPassword()
        assertEquals(0, auth.count("unlockWithPassword"))
        while (queued.isNotEmpty()) queued.removeFirst().run()
        advanceUntilIdle()
        assertEquals(1, auth.count("restore"))
        assertEquals(1, auth.count("unlockWithPassword"))
        assertEquals(listOf<Any?>(GOOD_PASSWORD), auth.args("unlockWithPassword"))
        assertFalse(screen.value.busy)
        assertFalse(screen.value.wrongPassword)
        assertNull(screen.value.error)
    }

    @Test
    fun aRegularLockNeverShowsTheUpdatedNotice() = runTest {
        auth.deviceUnlocks = false
        val (vm, screen) = open(locksInSession = 1)
        vm.onUnlockWithDevice()
        assertFalse(screen.value.updatedNotice)
    }

    @Test
    fun aCorruptJournalBlocksUnlockingAndLeavesEverythingInPlace() = runTest {
        Fixture.corrupt()
        val before = Fixture.sha256(Fixture.databaseFile)
        val legacy = Fixture.legacy(ByteArray(32) { 3 })
        val target = FakeTarget()
        val (vm, screen) = open(adoption = Fixture.adoption(legacy, target))
        assertTrue(screen.value.unreadable)
        assertFalse(screen.value.passwordShown)
        assertEquals(FIXTURE_USERNAME, screen.value.username)
        assertEquals(0, auth.count("restore"))
        assertEquals(0, target.calls)
        assertEquals(0, legacy.forgotten)
        vm.onUnlockWithDevice()
        vm.onUnlockWithPassword()
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onUnlockWithPassword()
        assertEquals(0, auth.count("unlockWithDevice"))
        assertEquals(0, auth.count("unlockWithPassword"))
        assertFalse(screen.value.passwordShown)
        assertEquals(before, Fixture.sha256(Fixture.databaseFile))
    }
}
