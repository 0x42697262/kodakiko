package dev.kodakiko.service.account

import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.Routes
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.HttpConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThrottlingTest : AccountTestBase() {
    private val tooManyRequests = 429
    private val retryAfterSeconds = 60

    @Test
    fun aThrottledSignInReportsRateLimitedAndWipesTheDerivedKeys() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        signUpAlice(server, createDevice(), sodium)
        sodium.clearSecrets()
        server.fail(Routes.authSignIn(), tooManyRequests, ErrorCode.RATE_LIMITED, 1, retryAfterSeconds)
        val app = startApp(server, createDevice(), sodium)

        val failure = authFailure { app.service.signIn(username = USERNAME, password = PASSWORD) }

        assertEquals(AuthFailure.RATE_LIMITED, failure.reason)
        assertEquals(1, server.count(Routes.authSignIn()))
        assertTrue(app.accountState.value is AccountState.SignedOut)
        sodium.assertWiped()
    }

    @Test
    fun throttledParametersReportRateLimitedAfterTheClientRetriesWithoutAnyArgon2idCall() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        signUpAlice(server, createDevice(), sodium)
        val callsBefore = sodium.argon2Calls
        server.fail(Routes.authParams(), tooManyRequests, ErrorCode.RATE_LIMITED, HttpConstants.RETRY_ATTEMPTS, 1)
        val app = startApp(server, createDevice(), sodium)

        val failure = authFailure { app.service.signIn(username = USERNAME, password = PASSWORD) }

        assertEquals(AuthFailure.RATE_LIMITED, failure.reason)
        assertEquals(callsBefore, sodium.argon2Calls)
        assertEquals(HttpConstants.RETRY_ATTEMPTS, server.count(Routes.authParams()))
        assertEquals(0, server.count(Routes.authSignIn()))
    }

    @Test
    fun aThrottledSignUpReportsRateLimitedWipesEverySecretAndDismissesTheSecuringScreen() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        server.fail(Routes.authSignUp(), tooManyRequests, ErrorCode.RATE_LIMITED, 1, retryAfterSeconds)
        val app = startApp(server, createDevice(), sodium)

        val failure = authFailure { app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null) }

        assertEquals(AuthFailure.RATE_LIMITED, failure.reason)
        assertEquals(1, app.showSecuringCalls)
        assertEquals(1, app.dismissSecuringCalls)
        assertTrue(app.accountState.value is AccountState.SignedOut)
        sodium.assertWiped()
    }

    @Test
    fun aThrottledRecoveryStartReportsRateLimitedBeforeAnyKeyDerivation() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val words = signUpAlice(server, createDevice(), sodium).recoveryWords
        val callsBefore = sodium.argon2Calls
        server.fail(Routes.authRecoveryStart(), tooManyRequests, ErrorCode.RATE_LIMITED, HttpConstants.RETRY_ATTEMPTS, 1)
        val app = startApp(server, createDevice(), sodium)

        val failure = authFailure { app.service.recoverAccount(username = USERNAME, words = words.joinToString(" "), newPassword = NEW_PASSWORD) }

        assertEquals(AuthFailure.RATE_LIMITED, failure.reason)
        assertEquals(callsBefore, sodium.argon2Calls)
        assertEquals(0, server.count(Routes.authRecoveryComplete()))
    }

    @Test
    fun aThrottledPasswordChangeReportsRateLimitedAndKeepsTheOldPassword() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = signUpAlice(server, device, sodium)
        val wrapBefore = device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)
        server.fail(Routes.authPassword(), tooManyRequests, ErrorCode.RATE_LIMITED, 1, retryAfterSeconds)

        val failure = authFailure { app.service.changePassword(PASSWORD, NEW_PASSWORD) }

        assertEquals(AuthFailure.RATE_LIMITED, failure.reason)
        assertArrayEquals(wrapBefore, device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK))
        assertTrue(app.service.verifyPassword(PASSWORD))
    }
}
