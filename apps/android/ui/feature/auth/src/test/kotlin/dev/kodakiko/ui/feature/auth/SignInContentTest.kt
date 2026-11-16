package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.errorBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignInContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private fun show(state: SignInState = SignInState()) {
        compose.show {
            SignInContent(
                state = state,
                onUsernameChange = { log += "username:$it" },
                onPasswordChange = { log += "password:$it" },
                onSubmit = { log += "submit" },
                onForgot = { log += "forgot" },
            )
        }
    }

    @Test
    fun showsTheFormWithTheV031Tags() {
        show(SignInState(username = "alice"))
        compose.onNodeWithTag(TestTags.SignIn.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.SignIn.TITLE).assertTextEquals("Sign In")
        compose.onNodeWithTag(TestTags.SignIn.USERNAME).assert(hasEditableText("alice")).assert(isPassword.not())
        compose.onNodeWithTag(TestTags.SignIn.PASSWORD).assert(isPassword)
        compose.onNodeWithTag(TestTags.SignIn.SUBMIT).performScrollTo().assertTextEquals("Sign In")
        compose.onNodeWithTag(TestTags.SignIn.FORGOT).performScrollTo().assertTextEquals("Forgot password?")
        assertFalse(compose.exists(TestTags.SignIn.DELETION_SCHEDULED))
    }

    @Test
    fun theFieldsReportTyping() {
        var state by mutableStateOf(SignInState())
        compose.show {
            SignInContent(
                state = state,
                onUsernameChange = { state = state.copy(username = it) },
                onPasswordChange = { state = state.copy(password = it) },
                onSubmit = { log += "submit" },
                onForgot = { log += "forgot" },
            )
        }
        compose.onNodeWithTag(TestTags.SignIn.USERNAME).performTextInput("alice")
        compose.onNodeWithTag(TestTags.SignIn.PASSWORD).performTextInput(GOOD_PASSWORD)
        assertEquals(SignInState(username = "alice", password = GOOD_PASSWORD), state)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun theUsernameMovesToThePasswordAndThePasswordSignsIn() {
        show()
        compose.onNodeWithTag(TestTags.SignIn.USERNAME).performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag(TestTags.SignIn.PASSWORD).assertIsFocused().performImeAction()
        assertEquals(listOf("submit"), log)
    }

    @Test
    fun signInAndForgotReportTheirTaps() {
        show()
        compose.onNodeWithTag(TestTags.SignIn.SUBMIT).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.SignIn.FORGOT).performScrollTo().performClick()
        assertEquals(listOf("submit", "forgot"), log)
    }

    @Test
    fun signInIsDisabledWhileBusy() {
        show(SignInState(busy = true))
        compose.onNodeWithTag(TestTags.SignIn.SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theCredentialErrorShowsTheTextMaestroWaitsFor() {
        show(SignInState(error = errorBanner(AuthError(AuthFailure.INVALID_CREDENTIALS))))
        compose.onNodeWithTag(TestTags.Errors.INVALID_CREDENTIALS).performScrollTo().assertTextEquals("Username or password is incorrect.")
    }

    @Test
    fun aScheduledDeletionIsAnnounced() {
        show(SignInState(deletionScheduled = "2026-11-21"))
        compose.onNodeWithTag(TestTags.SignIn.DELETION_SCHEDULED).performScrollTo()
            .assertTextEquals("Your account will be deleted on 2026-11-21. Sign in before then to keep it.")
    }
}
