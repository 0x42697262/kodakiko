package dev.kodakiko.service.account

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.Routes
import dev.kodakiko.data.net.CallConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignOutTest : AccountTestBase() {
    private val nanosPerMilli = 1_000_000L
    private val timerToleranceMs = 100L
    private val defaultTimeoutBoundMs = 15_000L

    @Test
    fun signOutGivesUpOnASilentServerAfterTheFiveSecondTimeoutAndStillSignsOut() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = signUpAlice(server, device, sodium)
        val accountKey = accountKeyOf(server)
        val keys: AccountKeys = app.unlocked().keys
        server.stallSignOut = true

        val started = System.nanoTime()
        app.service.signOut()
        val elapsedMs = (System.nanoTime() - started) / nanosPerMilli

        assertTrue(elapsedMs >= CallConstants.SIGN_OUT_TIMEOUT_MS - timerToleranceMs)
        assertTrue(elapsedMs < defaultTimeoutBoundMs)
        assertEquals(1, server.count(Routes.authSignOut()))
        assertEquals(AccountState.SignedOut(), app.accountState.value)
        assertNull(device.sessionStore.load(accountKey))
        assertNull(app.activeAccountKey.value)
        assertTrue(device.rowStore.destroyCalls.contains(accountKey))
        assertTrue(keys.masterKey.all { it == 0.toByte() })
    }

    @Test
    fun signOutRunsBeforeSignOutFirstAndSurvivesItsFailure() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val app = signUpAlice(server, createDevice(), sodium)
        val signOutCallsInHook = mutableListOf<Int>()
        var stateInHook: AccountState? = null
        app.beforeSignOut = {
            signOutCallsInHook.add(server.count(Routes.authSignOut()))
            stateInHook = app.accountState.value
            throw IllegalStateException("lock failed")
        }

        app.service.signOut()

        assertEquals(listOf(0), signOutCallsInHook)
        assertTrue(stateInHook is AccountState.Unlocked)
        assertEquals(1, server.count(Routes.authSignOut()))
        assertEquals(AccountState.SignedOut(), app.accountState.value)
    }

    @Test
    fun signOutWithoutAnAccountOnlyResetsTheState() = runTest {
        val sodium = TestSodium()
        val device = createDevice()
        val app = startApp(createFakeAuthServer(), device, sodium)
        app.inviteToken.value = UPPER_INVITE
        app.accountState.value = AccountState.SignedOut(USERNAME)

        app.service.signOut()

        assertTrue(device.rowStore.destroyCalls.isEmpty())
        assertTrue(device.fileStore.destroyCalls.isEmpty())
        assertTrue(app.keyVault.forgetCalls.isEmpty())
        assertEquals(AccountState.SignedOut(), app.accountState.value)
        assertNull(app.inviteToken.value)
    }
}
