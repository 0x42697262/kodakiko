package dev.kodakiko.ui.feature.auth

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.TestTags
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
class ConnectServerViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private val accountState = Observable<AccountState>(
        AccountState.Unlocked(AccountKeys(ByteArray(32), ByteArray(32), ByteArray(32)), userId = "user-1", username = "k007local"),
    )

    private fun TestScope.open(): Pair<ConnectServerViewModel, Observed<ConnectServerState, AuthEvent>> {
        val vm = ConnectServerViewModel(auth = auth, accountState = accountState)
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun prefillsTheLocalUsername() = runTest {
        val (_, screen) = open()
        assertEquals("k007local", screen.value.username)
        assertEquals("", screen.value.server)
        assertEquals("", screen.value.invite)
        assertEquals("", screen.value.password)
        assertFalse(screen.value.done)
    }

    @Test
    fun connectNeedsAServerAndAPassword() = runTest {
        val (vm, screen) = open()
        assertFalse(screen.value.canConnect)
        vm.onServerChange("   ")
        vm.onPasswordChange(GOOD_PASSWORD)
        assertFalse(screen.value.canConnect)
        vm.onServerChange("journal.example.test")
        assertTrue(screen.value.canConnect)
        vm.onPasswordChange("")
        assertFalse(screen.value.canConnect)
    }

    @Test
    fun anInviteLinkInTheInviteFieldIsSentAsItsToken() = runTest {
        val (vm, screen) = open()
        vm.onServerChange(INVITE_LINK)
        vm.onInviteChange(INVITE_LINK)
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onConnect()
        assertEquals(listOf<Any?>(INVITE_LINK, TOKEN, "k007local", GOOD_PASSWORD), auth.args("connectServer"))
        assertTrue(screen.value.done)
        assertEquals("", screen.value.password)
        assertFalse(screen.value.canConnect)
        assertNull(screen.value.error)
    }

    @Test
    fun anEmptyInviteIsSentAsNoToken() = runTest {
        val (vm, _) = open()
        vm.onServerChange("journal.example.test")
        vm.onInviteChange("   ")
        vm.onUsernameChange("newname")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onConnect()
        assertEquals(listOf<Any?>("journal.example.test", null, "newname", GOOD_PASSWORD), auth.args("connectServer"))
    }

    @Test
    fun anUnreadableInviteIsSentAsNoTokenLikeV031() = runTest {
        val (vm, _) = open()
        vm.onServerChange("journal.example.test")
        vm.onInviteChange("not a link")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onConnect()
        assertNull(auth.args("connectServer")[1])
    }

    @Test
    fun failuresShowTheirMessageKeepThePasswordAndStayUndone() = runTest {
        val expected = mapOf(
            AuthFailure.NOT_HTTPS to TestTags.Errors.NOT_HTTPS,
            AuthFailure.INVITE_INVALID to TestTags.Errors.INVITE_INVALID,
            AuthFailure.USERNAME_TAKEN to TestTags.Errors.USERNAME_TAKEN,
            AuthFailure.WRONG_PASSWORD to TestTags.Errors.WRONG_PASSWORD,
            AuthFailure.RATE_LIMITED to TestTags.Errors.RATE_LIMITED,
        )
        for ((reason, tag) in expected) {
            auth.failures["connectServer"] = AuthError(reason)
            val (vm, screen) = open()
            vm.onServerChange("journal.example.test")
            vm.onPasswordChange(GOOD_PASSWORD)
            vm.onConnect()
            assertEquals("$reason", tag, screen.value.error?.testTag)
            assertFalse("$reason", screen.value.done)
            assertEquals("$reason", GOOD_PASSWORD, screen.value.password)
            assertFalse("$reason", screen.value.busy)
        }
    }

    @Test
    fun isBusyWhileConnectingAndConnectsOnce() = runTest {
        val gate = auth.hold("connectServer")
        val (vm, screen) = open()
        vm.onServerChange("journal.example.test")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onConnect()
        assertTrue(screen.value.busy)
        vm.onConnect()
        assertEquals(1, auth.count("connectServer"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
        assertTrue(screen.value.done)
    }

    @Test
    fun aConnectedJournalCannotConnectAgain() = runTest {
        val (vm, screen) = open()
        vm.onServerChange("journal.example.test")
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onConnect()
        vm.onPasswordChange(GOOD_PASSWORD)
        vm.onConnect()
        assertTrue(screen.value.done)
        assertEquals(1, auth.count("connectServer"))
    }
}
