package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.format.errorBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class AuthScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
        compose.show(appTheme) { content() }
        compose.onRoot().captureScreenshot("auth/$name")
    }

    @Composable
    private fun Welcome(state: WelcomeState) = WelcomeContent(state = state, onServerChange = {}, onContinue = {}, onUseWithoutServer = {})

    @Composable
    private fun SignUp(state: SignUpState) =
        SignUpContent(state = state, onInviteChange = {}, onUsernameChange = {}, onPasswordChange = {}, onConfirmChange = {}, onSubmit = {})

    @Composable
    private fun RecoveryKey(state: RecoveryKeyState) = RecoveryKeyContent(state = state, onWrittenDown = {}, onTypedChange = { _, _ -> }, onConfirm = {})

    @Composable
    private fun SignIn(state: SignInState) = SignInContent(state = state, onUsernameChange = {}, onPasswordChange = {}, onSubmit = {}, onForgot = {})

    @Composable
    private fun Recover(state: RecoverState) =
        RecoverContent(state = state, onUsernameChange = {}, onWordsChange = {}, onNewPasswordChange = {}, onConfirmChange = {}, onSubmit = {})

    @Composable
    private fun LocalSetup(state: LocalSetupState) =
        LocalSetupContent(state = state, onUnderstoodChange = {}, onUsernameChange = {}, onPasswordChange = {}, onConfirmChange = {}, onCreate = {})

    @Composable
    private fun Connect(state: ConnectServerState) =
        ConnectServerContent(state = state, onServerChange = {}, onInviteChange = {}, onUsernameChange = {}, onPasswordChange = {}, onConnect = {}, onBack = {})

    private val confirm = RecoveryKeyState(words = RECOVERY_WORDS, confirming = true, positions = listOf(5, 10, 23), typed = listOf("absent", "acc", ""))

    @Test
    fun welcomeEmpty() = capture("welcome_empty") { Welcome(WelcomeState()) }

    @Test
    fun welcomeErrorBusy() = capture("welcome_error_busy") {
        Welcome(WelcomeState(server = "journal.example.test", busy = true, error = errorBanner(AuthError(AuthFailure.UNREACHABLE))))
    }

    @Test
    fun welcomeDark() = capture("welcome_dark", AppTheme.DARK) { Welcome(WelcomeState(server = INVITE_LINK)) }

    @Test
    fun signUpWithInvite() = capture("sign_up_invite") { SignUp(SignUpState(asksForInvite = true)) }

    @Test
    fun signUpHintsMismatchError() = capture("sign_up_hints_mismatch_error") {
        SignUp(
            SignUpState(
                username = "alice",
                password = "alice",
                confirm = "alic",
                hints = listOf(PasswordRule.TOO_SHORT, PasswordRule.EQUALS_USERNAME),
                mismatch = true,
                error = errorBanner(AuthError(AuthFailure.USERNAME_TAKEN)),
            ),
        )
    }

    @Test
    fun securing() = capture("securing") { SecuringContent() }

    @Test
    fun recoveryKeyWords() = capture("recovery_key_words") { RecoveryKey(RecoveryKeyState(words = RECOVERY_WORDS)) }

    @Test
    fun recoveryKeyConfirm() = capture("recovery_key_confirm") { RecoveryKey(confirm) }

    @Test
    fun recoveryKeyConfirmWrong() = capture("recovery_key_confirm_wrong") { RecoveryKey(confirm.copy(mismatch = true)) }

    @Test
    fun signInErrorAndDeletion() = capture("sign_in_error_deletion") {
        SignIn(SignInState(username = "alice", error = errorBanner(AuthError(AuthFailure.INVALID_CREDENTIALS)), deletionScheduled = "2026-11-21"))
    }

    @Test
    fun signInDark() = capture("sign_in_dark", AppTheme.DARK) { SignIn(SignInState(username = "alice", password = GOOD_PASSWORD)) }

    @Test
    fun recoverHints() = capture("recover_hints") {
        Recover(RecoverState(username = "alice", words = RECOVERY_WORDS.joinToString(" "), newPassword = "short", hints = listOf(PasswordRule.TOO_SHORT), mismatch = true))
    }

    @Test
    fun recoverLocal() = capture("recover_local") { Recover(RecoverState(showUsername = false)) }

    @Test
    fun cancelDeletion() = capture("cancel_deletion") { CancelDeletionContent(state = CancelDeletionState(date = "November 21, 2026"), onKeep = {}, onSignOut = {}) }

    @Test
    fun cancelDeletionError() = capture("cancel_deletion_error") {
        CancelDeletionContent(state = CancelDeletionState(date = "November 21, 2026", error = errorBanner(AuthError(AuthFailure.UNREACHABLE))), onKeep = {}, onSignOut = {})
    }

    @Test
    fun localSetupEmpty() = capture("local_setup_empty") { LocalSetup(LocalSetupState()) }

    @Test
    fun localSetupUnderstoodHints() = capture("local_setup_understood_hints") {
        LocalSetup(LocalSetupState(understood = true, username = "k007local", password = "short", confirm = "sh", hints = listOf(PasswordRule.TOO_SHORT), mismatch = true))
    }

    @Test
    fun connectReady() = capture("connect_ready") {
        Connect(ConnectServerState(server = "journal.example.test", invite = INVITE_LINK, username = "k007local", password = GOOD_PASSWORD))
    }

    @Test
    fun connectDone() = capture("connect_done") {
        Connect(ConnectServerState(server = "journal.example.test", username = "k007local", done = true))
    }
}
