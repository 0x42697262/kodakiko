package dev.kodakiko.ui.core.format

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags

@Immutable
data class BannerMessage(val testTag: String, @StringRes val message: Int, val position: Int? = null)

fun BannerMessage.text(resources: Resources): String =
    if (position == null) resources.getString(message) else resources.getString(message, position)

private val GENERIC_BANNER = BannerMessage(TestTags.Errors.GENERIC, R.string.errors_generic)

private fun failureBanner(reason: AuthFailure): BannerMessage = when (reason) {
    AuthFailure.WRONG_PASSWORD -> BannerMessage(TestTags.Errors.WRONG_PASSWORD, R.string.errors_wrong_password)
    AuthFailure.INVALID_CREDENTIALS -> BannerMessage(TestTags.Errors.INVALID_CREDENTIALS, R.string.errors_invalid_credentials)
    AuthFailure.UNREACHABLE -> BannerMessage(TestTags.Errors.UNREACHABLE, R.string.errors_unreachable)
    AuthFailure.NOT_HTTPS -> BannerMessage(TestTags.Errors.NOT_HTTPS, R.string.errors_not_https)
    AuthFailure.NOT_KODAKIKO -> BannerMessage(TestTags.Errors.NOT_KODAKIKO, R.string.errors_not_kodakiko)
    AuthFailure.INVITE_INVALID -> BannerMessage(TestTags.Errors.INVITE_INVALID, R.string.errors_invite_invalid)
    AuthFailure.USERNAME_TAKEN -> BannerMessage(TestTags.Errors.USERNAME_TAKEN, R.string.errors_username_taken)
    AuthFailure.USERNAME_INVALID -> BannerMessage(TestTags.Errors.USERNAME_INVALID, R.string.errors_username_invalid)
    AuthFailure.UNSAFE_SERVER -> BannerMessage(TestTags.Errors.UNSAFE_SERVER, R.string.errors_unsafe_server)
    AuthFailure.KDF_UNAVAILABLE -> BannerMessage(TestTags.Errors.KDF_UNAVAILABLE, R.string.errors_kdf_unavailable)
    AuthFailure.RATE_LIMITED -> BannerMessage(TestTags.Errors.RATE_LIMITED, R.string.errors_rate_limited)
    AuthFailure.RECOVERY_WORDS_INVALID -> BannerMessage(TestTags.Errors.RECOVERY_WORDS_INVALID, R.string.errors_recovery_words_invalid)
    AuthFailure.PASSWORD_POLICY, AuthFailure.GENERIC -> GENERIC_BANNER
}

fun errorBanner(error: AuthError): BannerMessage = when {
    error.reason == AuthFailure.PASSWORD_POLICY -> error.rules.firstOrNull()?.let { BannerMessage(passwordRuleTag(it), passwordRuleText(it)) } ?: GENERIC_BANNER
    error.reason == AuthFailure.RECOVERY_WORDS_INVALID && error.position != null ->
        BannerMessage(TestTags.Errors.RECOVERY_WORDS_INVALID, R.string.errors_recovery_words_invalid_position, error.position)
    else -> failureBanner(error.reason)
}

fun errorBanner(error: Throwable): BannerMessage = (error as? AuthError)?.let(::errorBanner) ?: GENERIC_BANNER

fun passwordRuleTag(rule: PasswordRule): String = when (rule) {
    PasswordRule.TOO_SHORT -> TestTags.Password.TOO_SHORT
    PasswordRule.EQUALS_USERNAME -> TestTags.Password.EQUALS_USERNAME
    PasswordRule.SINGLE_REPEATED_CHARACTER -> TestTags.Password.SINGLE_REPEATED_CHARACTER
}

@StringRes
fun passwordRuleText(rule: PasswordRule): Int = when (rule) {
    PasswordRule.TOO_SHORT -> R.string.password_too_short
    PasswordRule.EQUALS_USERNAME -> R.string.password_equals_username
    PasswordRule.SINGLE_REPEATED_CHARACTER -> R.string.password_single_repeated_character
}
