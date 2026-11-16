package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalSetupContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private fun show(state: LocalSetupState = LocalSetupState()) {
        compose.show {
            LocalSetupContent(
                state = state,
                onUnderstoodChange = { log += "understood:$it" },
                onUsernameChange = { log += "username:$it" },
                onPasswordChange = { log += "password:$it" },
                onConfirmChange = { log += "confirm:$it" },
                onCreate = { log += "create" },
            )
        }
    }

    @Test
    fun showsTheWarningTheSwitchAndTheFormWithTheV031Tags() {
        show()
        compose.onNodeWithTag(TestTags.LocalSetup.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.LocalSetup.TITLE).assertTextEquals("Use Without a Server")
        compose.onNodeWithTag(TestTags.LocalSetup.WARNING)
            .assertTextEquals("This phone will hold the only copy of your journal until you connect a server. Export it from Settings to keep a backup.")
        compose.onNodeWithTag(TestTags.LocalSetup.UNDERSTAND).performScrollTo().assertIsOff()
        compose.onNode(hasText("I understand")).assertExists()
        compose.onNodeWithTag(TestTags.LocalSetup.USERNAME).performScrollTo().assert(isPassword.not())
        compose.onNodeWithTag(TestTags.LocalSetup.PASSWORD).performScrollTo().assert(isPassword)
        compose.onNodeWithTag(TestTags.LocalSetup.CONFIRM).performScrollTo().assert(isPassword)
        compose.onNodeWithTag(TestTags.LocalSetup.CREATE).performScrollTo().assertTextEquals("Create Journal")
    }

    @Test
    fun tappingIUnderstandReportsTheNewValue() {
        show()
        compose.onNodeWithTag(TestTags.LocalSetup.UNDERSTAND).performScrollTo().performClick()
        assertEquals(listOf("understood:true"), log)
    }

    @Test
    fun anUnderstoodSwitchIsOnAndUntapsToFalse() {
        show(LocalSetupState(understood = true))
        compose.onNodeWithTag(TestTags.LocalSetup.UNDERSTAND).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf("understood:false"), log)
    }

    @Test
    fun createIsDisabledUntilUnderstood() {
        show()
        compose.onNodeWithTag(TestTags.LocalSetup.CREATE).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun createReportsTheTapOnceUnderstood() {
        show(LocalSetupState(understood = true))
        compose.onNodeWithTag(TestTags.LocalSetup.CREATE).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("create"), log)
    }

    @Test
    fun createIsDisabledWhileBusy() {
        show(LocalSetupState(understood = true, busy = true))
        compose.onNodeWithTag(TestTags.LocalSetup.CREATE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theFieldsReportTypingAndTheConfirmationCreates() {
        var state by mutableStateOf(LocalSetupState(understood = true))
        compose.show {
            LocalSetupContent(
                state = state,
                onUnderstoodChange = { state = state.copy(understood = it) },
                onUsernameChange = { state = state.copy(username = it) },
                onPasswordChange = { state = state.copy(password = it) },
                onConfirmChange = { state = state.copy(confirm = it) },
                onCreate = { log += "create" },
            )
        }
        compose.onNodeWithTag(TestTags.LocalSetup.USERNAME).performScrollTo().performTextInput("k007local")
        compose.onNodeWithTag(TestTags.LocalSetup.PASSWORD).performScrollTo().performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.LocalSetup.CONFIRM).performScrollTo().performTextInput(GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.LocalSetup.CONFIRM).performImeAction()
        assertEquals(LocalSetupState(understood = true, username = "k007local", password = GOOD_PASSWORD, confirm = GOOD_PASSWORD), state)
        assertEquals(listOf("create"), log)
    }

    @Test
    fun theKeyboardMovesFromTheUsernameToThePasswordToTheConfirmation() {
        show()
        compose.onNodeWithTag(TestTags.LocalSetup.USERNAME).performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag(TestTags.LocalSetup.PASSWORD).assertIsFocused().performImeAction()
        compose.onNodeWithTag(TestTags.LocalSetup.CONFIRM).assertIsFocused()
    }

    @Test
    fun hintsTheMismatchAndErrorsAreShown() {
        show(
            LocalSetupState(
                hints = listOf(PasswordRule.TOO_SHORT),
                mismatch = true,
                error = errorBanner(AuthError(AuthFailure.USERNAME_INVALID)),
            ),
        )
        compose.onNodeWithTag(TestTags.Password.TOO_SHORT).performScrollTo().assertTextEquals("Use at least 12 characters.")
        compose.onNode(hasText("The passwords don't match.")).performScrollTo().assertExists()
        compose.onNodeWithTag(TestTags.Errors.USERNAME_INVALID).performScrollTo()
            .assertTextEquals("Use 3–32 lowercase letters, digits, dots, dashes or underscores.")
    }
}
