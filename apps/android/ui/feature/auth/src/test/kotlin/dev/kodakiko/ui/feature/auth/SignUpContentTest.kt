package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.account.PasswordRule
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
class SignUpContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private fun show(state: SignUpState = SignUpState(asksForInvite = true)) {
        compose.show {
            SignUpContent(
                state = state,
                onInviteChange = { log += "invite:$it" },
                onUsernameChange = { log += "username:$it" },
                onPasswordChange = { log += "password:$it" },
                onConfirmChange = { log += "confirm:$it" },
                onSubmit = { log += "submit" },
            )
        }
    }

    @Test
    fun showsTheTitleAndTheFourFieldsWithTheirTags() {
        show()
        compose.onNodeWithTag(TestTags.SignUp.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.SignUp.TITLE).assertTextEquals("Create Your Account")
        for (tag in listOf(TestTags.SignUp.INVITE, TestTags.SignUp.USERNAME, TestTags.SignUp.PASSWORD, TestTags.SignUp.CONFIRM)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag(TestTags.SignUp.SUBMIT).performScrollTo().assertTextEquals("Create Account")
    }

    @Test
    fun theInviteFieldIsHiddenWhenItIsNotNeeded() {
        show(SignUpState(asksForInvite = false))
        assertFalse(compose.exists(TestTags.SignUp.INVITE))
    }

    @Test
    fun passwordsAreHiddenAndTheUsernameIsNot() {
        show()
        compose.onNodeWithTag(TestTags.SignUp.PASSWORD).assert(isPassword)
        compose.onNodeWithTag(TestTags.SignUp.CONFIRM).assert(isPassword)
        compose.onNodeWithTag(TestTags.SignUp.USERNAME).assert(isPassword.not())
        compose.onNodeWithTag(TestTags.SignUp.INVITE).assert(isPassword.not())
    }

    @Test
    fun theFieldsShowTheirValues() {
        show(SignUpState(asksForInvite = true, invite = TOKEN, username = "alice"))
        compose.onNodeWithTag(TestTags.SignUp.INVITE).assert(hasEditableText(TOKEN))
        compose.onNodeWithTag(TestTags.SignUp.USERNAME).assert(hasEditableText("alice"))
    }

    @Test
    fun eachFieldReportsTyping() {
        var state by mutableStateOf(SignUpState(asksForInvite = true))
        compose.show {
            SignUpContent(
                state = state,
                onInviteChange = { state = state.copy(invite = it) },
                onUsernameChange = { state = state.copy(username = it) },
                onPasswordChange = { state = state.copy(password = it) },
                onConfirmChange = { state = state.copy(confirm = it) },
                onSubmit = { log += "submit" },
            )
        }
        compose.onNodeWithTag(TestTags.SignUp.USERNAME).performTextInput("alice")
        compose.onNodeWithTag(TestTags.SignUp.INVITE).performTextInput(TOKEN)
        compose.onNodeWithTag(TestTags.SignUp.PASSWORD).performScrollTo().performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.SignUp.CONFIRM).performScrollTo().performTextInput(OTHER_PASSWORD)
        assertEquals(SignUpState(asksForInvite = true, invite = TOKEN, username = "alice", password = GOOD_PASSWORD, confirm = OTHER_PASSWORD), state)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun theKeyboardMovesThroughTheFieldsAndTheLastOneSubmits() {
        show()
        compose.onNodeWithTag(TestTags.SignUp.INVITE).performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag(TestTags.SignUp.USERNAME).assertIsFocused().performImeAction()
        compose.onNodeWithTag(TestTags.SignUp.PASSWORD).assertIsFocused().performImeAction()
        compose.onNodeWithTag(TestTags.SignUp.CONFIRM).assertIsFocused().performImeAction()
        assertEquals(listOf("submit"), log)
    }

    @Test
    fun eachFailingRuleShowsItsHint() {
        show(SignUpState(hints = listOf(PasswordRule.TOO_SHORT, PasswordRule.EQUALS_USERNAME)))
        compose.onNodeWithTag(TestTags.Password.TOO_SHORT).performScrollTo().assertTextEquals("Use at least 12 characters.")
        compose.onNodeWithTag(TestTags.Password.EQUALS_USERNAME).performScrollTo().assertTextEquals("Don't use your username as your password.")
        assertFalse(compose.exists(TestTags.Password.SINGLE_REPEATED_CHARACTER))
        assertFalse(compose.exists(TestTags.Password.MISMATCH))
    }

    @Test
    fun aMismatchShowsItsHint() {
        show(SignUpState(mismatch = true))
        compose.onNodeWithTag(TestTags.Password.MISMATCH).performScrollTo().assertTextEquals("Passwords don't match.")
    }

    @Test
    fun submitReportsTheTap() {
        show()
        compose.onNodeWithTag(TestTags.SignUp.SUBMIT).performScrollTo().performClick()
        assertEquals(listOf("submit"), log)
    }

    @Test
    fun submitIsDisabledWhileBusy() {
        show(SignUpState(busy = true))
        compose.onNodeWithTag(TestTags.SignUp.SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anErrorShowsItsTagAndText() {
        show(SignUpState(error = errorBanner(AuthError(AuthFailure.USERNAME_TAKEN))))
        compose.onNodeWithTag(TestTags.Errors.USERNAME_TAKEN).performScrollTo().assertTextEquals("That username is taken.")
    }
}
