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

enum class AuthFailure {
    WRONG_PASSWORD,
    INVALID_CREDENTIALS,
    UNREACHABLE,
    NOT_HTTPS,
    NOT_KODAKIKO,
    INVITE_INVALID,
    USERNAME_TAKEN,
    USERNAME_INVALID,
    UNSAFE_SERVER,
    KDF_UNAVAILABLE,
    RECOVERY_WORDS_INVALID,
    RATE_LIMITED,
    PASSWORD_POLICY,
    GENERIC,
}

class AuthError(
    val reason: AuthFailure,
    val position: Int? = null,
    val rules: List<PasswordRule> = emptyList(),
) : Exception(reason.name)

private val REASON_BY_API_CODE: Map<ErrorCode, AuthFailure> = mapOf(
    ErrorCode.INVALID_CREDENTIALS to AuthFailure.INVALID_CREDENTIALS,
    ErrorCode.INVITE_INVALID to AuthFailure.INVITE_INVALID,
    ErrorCode.USERNAME_TAKEN to AuthFailure.USERNAME_TAKEN,
    ErrorCode.RATE_LIMITED to AuthFailure.RATE_LIMITED,
)

fun toAuthError(error: Throwable): AuthError =
    when (error) {
        is AuthError -> error
        is NetworkError -> AuthError(AuthFailure.UNREACHABLE)
        is ApiError -> AuthError(REASON_BY_API_CODE[error.code] ?: AuthFailure.GENERIC)
        is HostileServerParamsError -> AuthError(AuthFailure.UNSAFE_SERVER)
        is KdfUnavailableError -> AuthError(AuthFailure.KDF_UNAVAILABLE)
        is WrongPasswordError -> AuthError(AuthFailure.WRONG_PASSWORD)
        is InvalidRecoveryWordsError ->
            AuthError(AuthFailure.RECOVERY_WORDS_INVALID, position = error.wordPosition?.plus(AccountConstants.FIRST_WORD_POSITION))
        is WrongRecoveryWordsError -> AuthError(AuthFailure.RECOVERY_WORDS_INVALID)
        else -> throw error
    }
