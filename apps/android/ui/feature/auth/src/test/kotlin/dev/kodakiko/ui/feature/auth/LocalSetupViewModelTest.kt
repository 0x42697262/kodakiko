package dev.kodakiko.ui.feature.auth

import dev.kodakiko.account.PasswordRule
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalSetupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()

    private fun TestScope.open(): Pair<LocalSetupViewModel, Observed<LocalSetupState, AuthEvent>> {
        val vm = LocalSetupViewModel(auth = auth)
        return vm to observe(vm.state, vm.events)
    }

    private fun LocalSetupViewModel.fill(username: String = "alice", password: String = GOOD_PASSWORD, confirm: String = password) {
        onUsernameChange(username)
        onPasswordChange(password)
        onConfirmChange(confirm)
    }

    @Test
    fun startsNotUnderstoodAndEmpty() = runTest {
        val (_, screen) = open()
        assertEquals(LocalSetupState(), screen.value)
        assertFalse(screen.value.understood)
        assertNull(screen.value.error)
    }

    @Test
    fun hintsWaitForTheFirstCreateAttempt() = runTest {
        val (vm, screen) = open()
        vm.fill(password = "short", confirm = "other")
        assertEquals(emptyList<PasswordRule>(), screen.value.hints)
        assertFalse(screen.value.mismatch)
        vm.onUnderstoodChange(true)
        vm.onCreate()
        assertEquals(listOf(PasswordRule.TOO_SHORT), screen.value.hints)
        assertTrue(screen.value.mismatch)
        assertEquals(0, auth.count("setUpLocal"))
    }

    @Test
    fun withoutIUnderstandNothingIsCreated() = runTest {
        val (vm, screen) = open()
        vm.fill()
        vm.onCreate()
        assertEquals(0, auth.count("setUpLocal"))
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun aMismatchedConfirmationCreatesNothing() = runTest {
        val (vm, screen) = open()
        vm.onUnderstoodChange(true)
        vm.fill(confirm = OTHER_PASSWORD)
        vm.onCreate()
        assertTrue(screen.value.mismatch)
        assertEquals(0, auth.count("setUpLocal"))
    }

    @Test
    fun createsTheJournalAndShowsTheRecoveryKey() = runTest {
        val (vm, screen) = open()
        vm.onUnderstoodChange(true)
        vm.fill()
        vm.onCreate()
        assertEquals(listOf<Any?>("alice", GOOD_PASSWORD), auth.args("setUpLocal"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.RecoveryKey)), screen.events)
        assertFalse(screen.value.busy)
    }

    @Test
    fun aRefusedUsernameShowsItsMessage() = runTest {
        auth.failures["setUpLocal"] = AuthError(AuthFailure.USERNAME_INVALID)
        val (vm, screen) = open()
        vm.onUnderstoodChange(true)
        vm.fill(username = "Al")
        vm.onCreate()
        assertEquals(TestTags.Errors.USERNAME_INVALID, screen.value.error?.testTag)
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun untickingIUnderstandBlocksTheNextCreate() = runTest {
        val (vm, screen) = open()
        vm.onUnderstoodChange(true)
        vm.onUnderstoodChange(false)
        vm.fill()
        vm.onCreate()
        assertFalse(screen.value.understood)
        assertEquals(0, auth.count("setUpLocal"))
    }

    @Test
    fun isBusyWhileCreatingAndCreatesOnce() = runTest {
        val gate = auth.hold("setUpLocal")
        val (vm, screen) = open()
        vm.onUnderstoodChange(true)
        vm.fill()
        vm.onCreate()
        assertTrue(screen.value.busy)
        vm.onCreate()
        assertEquals(1, auth.count("setUpLocal"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
    }
}
