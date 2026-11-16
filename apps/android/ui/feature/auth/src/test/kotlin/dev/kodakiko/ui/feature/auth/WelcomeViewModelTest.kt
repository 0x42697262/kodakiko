package dev.kodakiko.ui.feature.auth

import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.ServerChoice
import dev.kodakiko.service.account.ServerChoiceFailure
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
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
class WelcomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private val inviteToken = Observable<String?>("stale")

    private fun viewModel() = WelcomeViewModel(auth = auth, inviteToken = inviteToken)

    @Test
    fun startsWithAnEmptyServerFieldAndNoError() = runTest {
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        assertEquals(WelcomeState(server = "", busy = false, error = null), screen.value)
        assertEquals(0, auth.calls.size)
    }

    @Test
    fun continueSendsTheTypedTextAsIsAndOpensSignInWithoutAnInvite() = runTest {
        auth.choice = ServerChoice.Ok(BASE_URL, publicConfig(), inviteToken = null)
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onServerChange("  journal.example.test ")
        vm.onContinue()
        assertEquals(listOf<Any?>("  journal.example.test "), auth.args("chooseServer"))
        assertNull(inviteToken.value)
        assertEquals(listOf(AuthEvent.Push(Route.SignIn)), screen.events)
        assertFalse(screen.value.busy)
        assertNull(screen.value.error)
    }

    @Test
    fun aPastedInviteLinkKeepsTheTokenAndOpensSignUp() = runTest {
        auth.choice = ServerChoice.Ok(BASE_URL, publicConfig(), inviteToken = TOKEN)
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onServerChange(INVITE_LINK)
        vm.onContinue()
        assertEquals(listOf<Any?>(INVITE_LINK), auth.args("chooseServer"))
        assertEquals(TOKEN, inviteToken.value)
        assertEquals(listOf(AuthEvent.Push(Route.SignUp)), screen.events)
    }

    @Test
    fun aRefusedAddressShowsItsMessageAndStaysOnWelcome() = runTest {
        val expected = mapOf(
            ServerChoiceFailure.NOT_HTTPS to TestTags.Errors.NOT_HTTPS,
            ServerChoiceFailure.UNREACHABLE to TestTags.Errors.UNREACHABLE,
            ServerChoiceFailure.NOT_KODAKIKO to TestTags.Errors.NOT_KODAKIKO,
            ServerChoiceFailure.INVALID to TestTags.Errors.NOT_KODAKIKO,
        )
        for ((reason, tag) in expected) {
            auth.choice = ServerChoice.Failed(reason)
            val vm = viewModel()
            val screen = observe(vm.state, vm.events)
            vm.onServerChange("http://journal.example.test")
            vm.onContinue()
            assertEquals("$reason", tag, screen.value.error?.testTag)
            assertTrue("$reason", screen.events.isEmpty())
            assertFalse("$reason", screen.value.busy)
            assertEquals("$reason", "http://journal.example.test", screen.value.server)
        }
    }

    @Test
    fun anUnexpectedFailureShowsTheGenericMessage() = runTest {
        auth.failures["chooseServer"] = IllegalStateException("bug")
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onServerChange("journal.example.test")
        vm.onContinue()
        assertEquals(TestTags.Errors.GENERIC, screen.value.error?.testTag)
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun anAuthErrorKeepsItsOwnMessage() = runTest {
        auth.failures["chooseServer"] = AuthError(AuthFailure.RATE_LIMITED)
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onContinue()
        assertEquals(TestTags.Errors.RATE_LIMITED, screen.value.error?.testTag)
    }

    @Test
    fun isBusyWhileTheServerIsCheckedAndIgnoresASecondContinue() = runTest {
        auth.choice = ServerChoice.Ok(BASE_URL, publicConfig(), inviteToken = null)
        val gate = auth.hold("chooseServer")
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onServerChange("journal.example.test")
        vm.onContinue()
        assertTrue(screen.value.busy)
        vm.onContinue()
        assertEquals(1, auth.count("chooseServer"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
        assertEquals(listOf(AuthEvent.Push(Route.SignIn)), screen.events)
    }

    @Test
    fun aNewAttemptClearsThePreviousError() = runTest {
        auth.choice = ServerChoice.Failed(ServerChoiceFailure.UNREACHABLE)
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onContinue()
        assertEquals(TestTags.Errors.UNREACHABLE, screen.value.error?.testTag)
        auth.choice = ServerChoice.Ok(BASE_URL, publicConfig(), inviteToken = null)
        val gate = auth.hold("chooseServer")
        vm.onContinue()
        assertNull(screen.value.error)
        gate.complete(Unit)
        assertNull(screen.value.error)
    }

    @Test
    fun useWithoutAServerOpensLocalSetupWithoutTouchingTheServer() = runTest {
        val vm = viewModel()
        val screen = observe(vm.state, vm.events)
        vm.onUseWithoutServer()
        assertEquals(listOf(AuthEvent.Push(Route.LocalSetup)), screen.events)
        assertEquals(0, auth.count("chooseServer"))
    }
}
