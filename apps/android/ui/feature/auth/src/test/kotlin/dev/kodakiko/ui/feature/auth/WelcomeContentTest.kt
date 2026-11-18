package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
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
class WelcomeContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private fun show(state: WelcomeState = WelcomeState()) {
        compose.show {
            WelcomeContent(
                state = state,
                onServerChange = { log += "server:$it" },
                onContinue = { log += "continue" },
                onUseWithoutServer = { log += "local" },
            )
        }
    }

    @Test
    fun showsTheTitleSubtitleAndAnEmptyServerField() {
        show()
        compose.onNodeWithTag(TestTags.Welcome.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.Welcome.TITLE).assertTextEquals("Kodakiko")
        compose.onNodeWithTag(TestTags.Welcome.SUBTITLE).assertTextEquals("Your private journal of photos, videos and notes.")
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).assertIsDisplayed().assert(hasEditableText(""))
        compose.onNode(hasText("Invite link or server address")).assertExists()
    }

    @Test
    fun theServerFieldShowsTheState() {
        show(WelcomeState(server = "journal.example.test"))
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).assert(hasEditableText("journal.example.test"))
    }

    @Test
    fun theServerFieldReportsTyping() {
        show()
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).performTextInput(INVITE_LINK)
        assertEquals(listOf("server:$INVITE_LINK"), log)
    }

    @Test
    fun theKeyboardActionOfTheServerFieldContinues() {
        show(WelcomeState(server = "journal.example.test"))
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).performImeAction()
        assertEquals(listOf("continue"), log)
    }

    @Test
    fun continueAndUseWithoutAServerReportTheirTaps() {
        show()
        compose.onNodeWithTag(TestTags.Welcome.CONTINUE).assertTextEquals("Continue").assertIsEnabled().performClick()
        compose.onNodeWithTag(TestTags.Welcome.LOCAL).assertTextEquals("Use Without a Server").performClick()
        assertEquals(listOf("continue", "local"), log)
    }

    @Test
    fun continueIsDisabledWhileBusy() {
        show(WelcomeState(server = "journal.example.test", busy = true))
        compose.onNodeWithTag(TestTags.Welcome.CONTINUE).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun anErrorShowsItsTagAndText() {
        show(WelcomeState(error = errorBanner(AuthError(AuthFailure.NOT_KODAKIKO))))
        compose.onNodeWithTag(TestTags.Errors.NOT_KODAKIKO).assertIsDisplayed().assertTextEquals("That address isn't a Kodakiko server.")
    }
}
