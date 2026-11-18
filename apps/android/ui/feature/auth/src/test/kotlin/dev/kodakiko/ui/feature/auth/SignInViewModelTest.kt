package dev.kodakiko.ui.feature.auth

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.text
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
class SignInViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private val accountState = Observable<AccountState>(AccountState.SignedOut())

    private fun TestScope.open(): Pair<SignInViewModel, Observed<SignInState, AuthEvent>> {
        val vm = SignInViewModel(auth = auth, accountState = accountState)
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun prefillsTheRememberedUsernameOfASignedOutAccount() = runTest {
        accountState.value = AccountState.SignedOut("alice")
        val (_, screen) = open()
        assertEquals("alice", screen.value.username)
        assertEquals("", screen.value.password)
    }

    @Test
    fun startsEmptyWithoutARememberedUsername() = runTest {
        accountState.value = AccountState.NoServer
        val (_, screen) = open()
        assertEquals("", screen.value.username)
        assertNull(screen.value.deletionScheduled)
        assertNull(screen.value.error)
    }

    @Test
    fun showsTheScheduledDeletionDate() = runTest {
        auth.deletionNotice = "2026-11-21T10:00:00.000Z"
        val (_, screen) = open()
        assertEquals("2026-11-21", screen.value.deletionScheduled)
    }

    @Test
    fun signsInWithWhatWasTypedAndGoesHome() = runTest {
        val (vm, screen) = open()
        vm.onUsernameChange("Alice")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onSubmit()
        assertEquals(listOf<Any?>("Alice", GOOD_PASSWORD), auth.args("signIn"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
        assertFalse(screen.value.busy)
    }

    @Test
    fun aWrongPasswordAndAnUnknownUserShowTheSameMessageAndClearThePassword() = runTest {
        for (reason in listOf(AuthFailure.WRONG_PASSWORD, AuthFailure.INVALID_CREDENTIALS)) {
            auth.failures["signIn"] = AuthError(reason)
            val (vm, screen) = open()
            vm.onUsernameChange("alice")
            vm.onPasswordChange("wrong password here")
            vm.onSubmit()
            assertEquals("$reason", "", screen.value.password)
            assertEquals("$reason", "alice", screen.value.username)
            assertTrue("$reason", screen.events.isEmpty())
            assertEquals("$reason", "Username or password is incorrect.", screen.value.error!!.text(resources))
        }
    }

    @Test
    fun anUnknownUserShowsTheInvalidCredentialsTagMaestroWaitsFor() = runTest {
        auth.failures["signIn"] = AuthError(AuthFailure.INVALID_CREDENTIALS)
        val (vm, screen) = open()
        vm.onSubmit()
        assertEquals(TestTags.Errors.INVALID_CREDENTIALS, screen.value.error?.testTag)
    }

    @Test
    fun throttlingShowsTheRateLimitMessage() = runTest {
        auth.failures["signIn"] = AuthError(AuthFailure.RATE_LIMITED)
        val (vm, screen) = open()
        vm.onUsernameChange("alice")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onSubmit()
        assertEquals(TestTags.Errors.RATE_LIMITED, screen.value.error?.testTag)
        assertEquals("Too many attempts. Wait a minute and try again.", screen.value.error!!.text(resources))
    }

    @Test
    fun otherFailuresShowTheirMessages() = runTest {
        val expected = mapOf(
            AuthFailure.UNREACHABLE to TestTags.Errors.UNREACHABLE,
            AuthFailure.UNSAFE_SERVER to TestTags.Errors.UNSAFE_SERVER,
            AuthFailure.KDF_UNAVAILABLE to TestTags.Errors.KDF_UNAVAILABLE,
        )
        for ((reason, tag) in expected) {
            auth.failures["signIn"] = AuthError(reason)
            val (vm, screen) = open()
            vm.onSubmit()
            assertEquals("$reason", tag, screen.value.error?.testTag)
        }
        auth.failures["signIn"] = RuntimeException("bug")
        val (vm, screen) = open()
        vm.onSubmit()
        assertEquals(TestTags.Errors.GENERIC, screen.value.error?.testTag)
    }

    @Test
    fun isBusyWhileSigningInAndSignsInOnce() = runTest {
        val gate = auth.hold("signIn")
        val (vm, screen) = open()
        vm.onSubmit()
        assertTrue(screen.value.busy)
        vm.onSubmit()
        assertEquals(1, auth.count("signIn"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
    }

    @Test
    fun aNewAttemptClearsTheOldError() = runTest {
        auth.failures["signIn"] = AuthError(AuthFailure.WRONG_PASSWORD)
        val (vm, screen) = open()
        vm.onSubmit()
        assertEquals(TestTags.Errors.WRONG_PASSWORD, screen.value.error?.testTag)
        auth.failures.remove("signIn")
        val gate = auth.hold("signIn")
        vm.onSubmit()
        assertNull(screen.value.error)
        gate.complete(Unit)
    }

    @Test
    fun forgotOpensRecovery() = runTest {
        val (vm, screen) = open()
        vm.onForgot()
        assertEquals(listOf(AuthEvent.Push(Route.Recover)), screen.events)
        assertEquals(0, auth.calls.count { it.name != "activeUsername" })
    }
}
