package dev.kodakiko.ui.feature.auth

import dev.kodakiko.account.PasswordRule
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.Observable
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
class SignUpViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private val config = FakeConfigService(publicConfig(SignupMode.INVITE))
    private val inviteToken = Observable<String?>(null)

    private fun TestScope.open(): Pair<SignUpViewModel, Observed<SignUpState, AuthEvent>> {
        val vm = SignUpViewModel(auth = auth, config = config, inviteToken = inviteToken)
        return vm to observe(vm.state, vm.events)
    }

    private fun SignUpViewModel.fill(username: String = "alice", password: String = GOOD_PASSWORD, confirm: String = password) {
        onUsernameChange(username)
        onPasswordChange(password)
        onConfirmChange(confirm)
    }

    @Test
    fun asksForAnInviteOnAnInviteOnlyServerWithoutAKnownToken() = runTest {
        val (_, screen) = open()
        assertTrue(screen.value.asksForInvite)
    }

    @Test
    fun doesNotAskForAnInviteWhenWelcomeAlreadyHasOne() = runTest {
        inviteToken.value = TOKEN
        val (_, screen) = open()
        assertFalse(screen.value.asksForInvite)
    }

    @Test
    fun doesNotAskForAnInviteOnAnOpenServer() = runTest {
        config.config = publicConfig(SignupMode.OPEN)
        val (_, screen) = open()
        assertFalse(screen.value.asksForInvite)
    }

    @Test
    fun anUnknownConfigFallsBackToInviteOnly() = runTest {
        config.config = null
        val (_, screen) = open()
        assertTrue(screen.value.asksForInvite)
    }

    @Test
    fun showsNoHintsUntilThePasswordIsTypedThenEachFailingRule() = runTest {
        val (vm, screen) = open()
        vm.onUsernameChange("alice")
        assertEquals(emptyList<PasswordRule>(), screen.value.hints)
        assertFalse(screen.value.mismatch)
        vm.onPasswordChange("short")
        assertEquals(listOf(PasswordRule.TOO_SHORT), screen.value.hints)
        vm.onPasswordChange("aaaaaaaaaaaa")
        assertEquals(listOf(PasswordRule.SINGLE_REPEATED_CHARACTER), screen.value.hints)
        vm.onPasswordChange(GOOD_PASSWORD)
        assertEquals(emptyList<PasswordRule>(), screen.value.hints)
    }

    @Test
    fun showsTheMismatchOnceTouchedAndTheConfirmationDiffers() = runTest {
        val (vm, screen) = open()
        vm.onPasswordChange(GOOD_PASSWORD)
        assertTrue(screen.value.mismatch)
        vm.onConfirmChange(GOOD_PASSWORD)
        assertFalse(screen.value.mismatch)
    }

    @Test
    fun submittingAnUntouchedFormShowsTheHintsAndCreatesNothing() = runTest {
        val (vm, screen) = open()
        vm.onSubmit()
        assertEquals(listOf(PasswordRule.TOO_SHORT, PasswordRule.EQUALS_USERNAME), screen.value.hints)
        assertEquals(0, auth.count("signUp"))
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun aMismatchedConfirmationCreatesNothing() = runTest {
        inviteToken.value = TOKEN
        val (vm, screen) = open()
        vm.fill(confirm = OTHER_PASSWORD)
        vm.onSubmit()
        assertTrue(screen.value.mismatch)
        assertEquals(0, auth.count("signUp"))
    }

    @Test
    fun aPastedInviteLinkIsSentAsItsToken() = runTest {
        val (vm, screen) = open()
        vm.onInviteChange(" $INVITE_LINK ")
        vm.fill()
        vm.onSubmit()
        assertEquals(listOf<Any?>("alice", GOOD_PASSWORD, TOKEN), auth.args("signUp"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.RecoveryKey)), screen.events)
    }

    @Test
    fun anInvalidInviteIsRefusedWithoutCallingTheServer() = runTest {
        val (vm, screen) = open()
        vm.onInviteChange("https://journal.example.test/invite")
        vm.fill()
        vm.onSubmit()
        assertEquals(0, auth.count("signUp"))
        assertEquals(TestTags.Errors.INVITE_INVALID, screen.value.error?.testTag)
        assertFalse(screen.value.busy)
    }

    @Test
    fun theTokenFromWelcomeWinsOverTheField() = runTest {
        inviteToken.value = TOKEN
        val (vm, _) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals(TOKEN, auth.args("signUp")[2])
    }

    @Test
    fun anOpenServerSignsUpWithoutAToken() = runTest {
        config.config = publicConfig(SignupMode.OPEN)
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals(listOf<Any?>("alice", GOOD_PASSWORD, null), auth.args("signUp"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.RecoveryKey)), screen.events)
    }

    @Test
    fun serverFailuresShowTheirMessageAndStayOnTheForm() = runTest {
        for (reason in listOf(AuthFailure.USERNAME_TAKEN, AuthFailure.INVITE_INVALID, AuthFailure.RATE_LIMITED, AuthFailure.USERNAME_INVALID, AuthFailure.KDF_UNAVAILABLE)) {
            auth.failures["signUp"] = AuthError(reason)
            inviteToken.value = TOKEN
            val (vm, screen) = open()
            vm.fill()
            vm.onSubmit()
            assertEquals("$reason", errorTag(reason), screen.value.error?.testTag)
            assertTrue("$reason", screen.events.isEmpty())
            assertFalse("$reason", screen.value.busy)
            assertEquals("$reason", "alice", screen.value.username)
        }
    }

    private fun errorTag(reason: AuthFailure): String = when (reason) {
        AuthFailure.USERNAME_TAKEN -> TestTags.Errors.USERNAME_TAKEN
        AuthFailure.INVITE_INVALID -> TestTags.Errors.INVITE_INVALID
        AuthFailure.RATE_LIMITED -> TestTags.Errors.RATE_LIMITED
        AuthFailure.USERNAME_INVALID -> TestTags.Errors.USERNAME_INVALID
        AuthFailure.KDF_UNAVAILABLE -> TestTags.Errors.KDF_UNAVAILABLE
        else -> error("unused")
    }

    @Test
    fun aPolicyFailureFromTheServiceShowsItsFirstRule() = runTest {
        auth.failures["signUp"] = AuthError(AuthFailure.PASSWORD_POLICY, rules = listOf(PasswordRule.EQUALS_USERNAME))
        inviteToken.value = TOKEN
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals(TestTags.Password.EQUALS_USERNAME, screen.value.error?.testTag)
    }

    @Test
    fun isBusyWhileCreatingAndClearsTheOldError() = runTest {
        inviteToken.value = TOKEN
        auth.failures["signUp"] = AuthError(AuthFailure.UNREACHABLE)
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals(TestTags.Errors.UNREACHABLE, screen.value.error?.testTag)
        auth.failures.remove("signUp")
        val gate = auth.hold("signUp")
        vm.onSubmit()
        assertTrue(screen.value.busy)
        assertNull(screen.value.error)
        vm.onSubmit()
        assertEquals(2, auth.count("signUp"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.RecoveryKey)), screen.events)
    }
}
