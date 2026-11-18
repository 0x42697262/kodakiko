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
class RecoverContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private fun show(state: RecoverState = RecoverState()) {
        compose.show {
            RecoverContent(
                state = state,
                onUsernameChange = { log += "username:$it" },
                onWordsChange = { log += "words:$it" },
                onNewPasswordChange = { log += "new:$it" },
                onConfirmChange = { log += "confirm:$it" },
                onSubmit = { log += "submit" },
            )
        }
    }

    @Test
    fun showsTheFormWithTheV031Tags() {
        show(RecoverState(username = "alice"))
        compose.onNodeWithTag(TestTags.Recover.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.Recover.TITLE).assertTextEquals("Recover Your Account")
        compose.onNodeWithTag(TestTags.Recover.USERNAME).assert(hasEditableText("alice"))
        compose.onNodeWithTag(TestTags.Recover.WORDS).assert(isPassword.not())
        compose.onNodeWithTag(TestTags.Recover.NEW_PASSWORD).performScrollTo().assert(isPassword)
        compose.onNodeWithTag(TestTags.Recover.CONFIRM).performScrollTo().assert(isPassword)
        compose.onNodeWithTag(TestTags.Recover.SUBMIT).performScrollTo().assertTextEquals("Recover Account")
    }

    @Test
    fun aLocalJournalHasNoUsernameField() {
        show(RecoverState(showUsername = false))
        assertFalse(compose.exists(TestTags.Recover.USERNAME))
        compose.onNodeWithTag(TestTags.Recover.WORDS).assertExists()
    }

    @Test
    fun theFieldsReportTyping() {
        var state by mutableStateOf(RecoverState())
        compose.show {
            RecoverContent(
                state = state,
                onUsernameChange = { state = state.copy(username = it) },
                onWordsChange = { state = state.copy(words = it) },
                onNewPasswordChange = { state = state.copy(newPassword = it) },
                onConfirmChange = { state = state.copy(confirm = it) },
                onSubmit = { log += "submit" },
            )
        }
        val words = RECOVERY_WORDS.joinToString(" ")
        compose.onNodeWithTag(TestTags.Recover.USERNAME).performTextInput("alice")
        compose.onNodeWithTag(TestTags.Recover.WORDS).performTextInput(words)
        compose.onNodeWithTag(TestTags.Recover.NEW_PASSWORD).performScrollTo().performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.Recover.CONFIRM).performScrollTo().performTextInput(GOOD_PASSWORD)
        assertEquals(RecoverState(username = "alice", words = words, newPassword = GOOD_PASSWORD, confirm = GOOD_PASSWORD), state)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun theKeyboardMovesFromThePasswordToTheConfirmationWhichRecovers() {
        show()
        compose.onNodeWithTag(TestTags.Recover.USERNAME).performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag(TestTags.Recover.WORDS).assertIsFocused()
        compose.onNodeWithTag(TestTags.Recover.NEW_PASSWORD).performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag(TestTags.Recover.CONFIRM).assertIsFocused().performImeAction()
        assertEquals(listOf("submit"), log)
    }

    @Test
    fun hintsAndTheMismatchAreShown() {
        show(RecoverState(hints = listOf(PasswordRule.SINGLE_REPEATED_CHARACTER), mismatch = true))
        compose.onNodeWithTag(TestTags.Password.SINGLE_REPEATED_CHARACTER).performScrollTo().assertTextEquals("Don't use one character repeated.")
        compose.onNodeWithTag(TestTags.Password.MISMATCH).performScrollTo().assertTextEquals("Passwords don't match.")
    }

    @Test
    fun recoverReportsTheTap() {
        show()
        compose.onNodeWithTag(TestTags.Recover.SUBMIT).performScrollTo().performClick()
        assertEquals(listOf("submit"), log)
    }

    @Test
    fun recoverIsDisabledWhileBusy() {
        show(RecoverState(busy = true))
        compose.onNodeWithTag(TestTags.Recover.SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anUnknownWordShowsItsPosition() {
        show(RecoverState(error = errorBanner(AuthError(AuthFailure.RECOVERY_WORDS_INVALID, position = 7))))
        compose.onNodeWithTag(TestTags.Errors.RECOVERY_WORDS_INVALID).performScrollTo().assertTextEquals("Word 7 isn't in the recovery word list.")
    }
}
