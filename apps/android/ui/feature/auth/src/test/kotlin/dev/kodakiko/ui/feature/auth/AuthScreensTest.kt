package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.ServerChoice
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class AuthScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val auth = FakeAuthService()
    private val navigator = FakeNavigator(listOf(Route.Welcome))

    private fun showScreen(content: @Composable () -> Unit) {
        compose.show {
            CompositionLocalProvider(LocalRouteNavigator provides navigator) { content() }
        }
    }

    private fun awaitLog(expected: List<String>) {
        compose.waitUntil(WAIT_MS) { navigator.log == expected }
        assertEquals(expected, navigator.log)
    }

    @Test
    fun welcomeOpensSignInForAServerWithoutInvite() {
        auth.choice = ServerChoice.Ok(BASE_URL, publicConfig(), inviteToken = null)
        val viewModel = WelcomeViewModel(auth = auth, inviteToken = Observable<String?>(null))
        showScreen { WelcomeScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).performTextInput("journal.example.test")
        compose.onNodeWithTag(TestTags.Welcome.CONTINUE).performClick()
        awaitLog(listOf("push /sign-in"))
        assertEquals(listOf<Any?>("journal.example.test"), auth.args("chooseServer"))
    }

    @Test
    fun welcomeOpensLocalSetup() {
        val viewModel = WelcomeViewModel(auth = auth, inviteToken = Observable<String?>(null))
        showScreen { WelcomeScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.Welcome.LOCAL).performClick()
        awaitLog(listOf("push /local-setup"))
    }

    @Test
    fun signUpShowsTheRecoveryKeyAfterCreating() {
        val viewModel = SignUpViewModel(auth = auth, config = FakeConfigService(publicConfig(SignupMode.OPEN)), inviteToken = Observable<String?>(null))
        showScreen { SignUpScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.SignUp.USERNAME).performTextInput("alice")
        compose.onNodeWithTag(TestTags.SignUp.PASSWORD).performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.SignUp.CONFIRM).performScrollTo().performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.SignUp.SUBMIT).performScrollTo().performClick()
        awaitLog(listOf("replaceAll /recovery-key"))
        assertEquals(listOf<Any?>("alice", GOOD_PASSWORD, null), auth.args("signUp"))
    }

    @Test
    fun recoveryKeyGoesThroughTheThreeWordCheckAndHome() {
        var draw = 0
        val counter = RandomSource { byteArrayOf((draw++).toByte()) }
        val viewModel = RecoveryKeyViewModel(auth = auth, random = counter)
        showScreen { RecoveryKeyScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.RecoveryKey.DONE).performScrollTo().performClick()
        for (index in 0..2) {
            compose.onNodeWithTag(TestTags.listItem(TestTags.RecoveryKey.CONFIRM_FIELD, index)).performScrollTo().performTextInput(RECOVERY_WORDS[index])
        }
        compose.onNodeWithTag(TestTags.RecoveryKey.CONFIRM_SUBMIT).performScrollTo().performClick()
        awaitLog(listOf("replaceAll /"))
        assertEquals(1, auth.count("confirmRecoveryKeySaved"))
    }

    @Test
    fun signInForgotOpensRecovery() {
        val viewModel = SignInViewModel(auth = auth, accountState = Observable<AccountState>(AccountState.SignedOut()))
        showScreen { SignInScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.SignIn.FORGOT).performScrollTo().performClick()
        awaitLog(listOf("push /recover"))
    }

    @Test
    fun cancelDeletionSignOutReturnsToWelcome() {
        val viewModel = CancelDeletionViewModel(auth = auth, dueAt = DeletionDueAt { null }, zone = ZoneOffset.UTC)
        showScreen { CancelDeletionScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.CancelDeletion.SIGN_OUT).performScrollTo().performClick()
        awaitLog(listOf("replaceAll /welcome"))
    }

    @Test
    fun connectServerGoesBackWithTheUpButton() {
        val state = AccountState.Unlocked(AccountKeys(ByteArray(32), ByteArray(32), ByteArray(32)), "user-1", "k007local")
        navigator.push(Route.SettingsConnect)
        navigator.log.clear()
        val viewModel = ConnectServerViewModel(auth = auth, accountState = Observable<AccountState>(state))
        showScreen { ConnectServerScreen(viewModel = viewModel) }
        compose.onNodeWithTag(TestTags.Connect.BACK).performClick()
        awaitLog(listOf("pop"))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
