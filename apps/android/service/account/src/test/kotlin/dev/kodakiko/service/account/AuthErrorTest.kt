package dev.kodakiko.service.account

import dev.kodakiko.account.HostileServerParamsError
import dev.kodakiko.account.KdfUnavailableError
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.account.WrongPasswordError
import dev.kodakiko.account.WrongRecoveryWordsError
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.crypto.InvalidRecoveryWordsError
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthErrorTest {
    @Test
    fun mapsTransportAndApiFailuresToAReason() {
        assertEquals(AuthFailure.UNREACHABLE, toAuthError(NetworkError()).reason)
        assertEquals(AuthFailure.INVALID_CREDENTIALS, toAuthError(ApiError(401, ErrorCode.INVALID_CREDENTIALS, null, null)).reason)
        assertEquals(AuthFailure.INVITE_INVALID, toAuthError(ApiError(403, ErrorCode.INVITE_INVALID, null, null)).reason)
        assertEquals(AuthFailure.USERNAME_TAKEN, toAuthError(ApiError(409, ErrorCode.USERNAME_TAKEN, null, null)).reason)
        assertEquals(AuthFailure.RATE_LIMITED, toAuthError(ApiError(429, ErrorCode.RATE_LIMITED, null, null)).reason)
        assertEquals(AuthFailure.GENERIC, toAuthError(ApiError(500, ErrorCode.INTERNAL, null, null)).reason)
    }

    @Test
    fun rethrowsUnexpectedErrors() {
        assertThrows(IllegalStateException::class.java) { toAuthError(IllegalStateException("bug")) }
    }

    @Test
    fun mapsAThrottledAnswerWithRetryAfterToRateLimited() {
        assertEquals(AuthFailure.RATE_LIMITED, toAuthError(ApiError(429, ErrorCode.RATE_LIMITED, null, "req-1", 60.0)).reason)
    }

    @Test
    fun mapsTheAccountCoreErrorsToAReason() {
        assertEquals(AuthFailure.UNSAFE_SERVER, toAuthError(HostileServerParamsError()).reason)
        assertEquals(AuthFailure.KDF_UNAVAILABLE, toAuthError(KdfUnavailableError()).reason)
        assertEquals(AuthFailure.WRONG_PASSWORD, toAuthError(WrongPasswordError()).reason)
        val unknownWord = toAuthError(InvalidRecoveryWordsError(4, "unknown"))
        assertEquals(AuthFailure.RECOVERY_WORDS_INVALID, unknownWord.reason)
        assertEquals(5, unknownWord.position)
        val badChecksum = toAuthError(InvalidRecoveryWordsError(null, "checksum"))
        assertEquals(AuthFailure.RECOVERY_WORDS_INVALID, badChecksum.reason)
        assertNull(badChecksum.position)
        val wrongWords = toAuthError(WrongRecoveryWordsError())
        assertEquals(AuthFailure.RECOVERY_WORDS_INVALID, wrongWords.reason)
        assertNull(wrongWords.position)
    }

    @Test
    fun keepsAnAuthErrorAsItIs() {
        val original = AuthError(AuthFailure.PASSWORD_POLICY, rules = listOf(PasswordRule.TOO_SHORT))
        assertSame(original, toAuthError(original))
        assertEquals(listOf(PasswordRule.TOO_SHORT), original.rules)
        assertNull(original.position)
        assertEquals(emptyList<PasswordRule>(), AuthError(AuthFailure.GENERIC).rules)
    }
}
