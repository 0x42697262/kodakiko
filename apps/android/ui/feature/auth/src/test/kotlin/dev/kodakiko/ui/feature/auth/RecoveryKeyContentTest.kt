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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.ui.core.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import dev.kodakiko.ui.core.R as CoreR

@RunWith(RobolectricTestRunner::class)
class RecoveryKeyContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private val confirming = RecoveryKeyState(
        words = RECOVERY_WORDS,
        confirming = true,
        positions = listOf(5, 10, 23),
        typed = listOf("absent", "", ""),
    )

    private fun word(position: Int): String = TestTags.listItem(TestTags.RecoveryKey.WORD, position)

    private fun label(index: Int): String = TestTags.listItem(TestTags.RecoveryKey.CONFIRM_LABEL, index)

    private fun field(index: Int): String = TestTags.listItem(TestTags.RecoveryKey.CONFIRM_FIELD, index)

    private fun show(state: RecoveryKeyState) {
        compose.show {
            RecoveryKeyContent(
                state = state,
                onWrittenDown = { log += "done" },
                onTypedChange = { index, value -> log += "typed:$index:$value" },
                onConfirm = { log += "confirm" },
            )
        }
    }

    @Test
    fun showsNothingToCopyWhileTheWordsLoad() {
        show(RecoveryKeyState())
        compose.onNodeWithTag(TestTags.RecoveryKey.SCREEN).assertExists()
        assertFalse(compose.exists(word(1)))
        assertFalse(compose.exists(TestTags.RecoveryKey.DONE))
    }

    @Test
    fun showsTheTitleTheWarningAndEveryWordUnderItsOwnTag() {
        show(RecoveryKeyState(words = RECOVERY_WORDS))
        compose.onNodeWithTag(TestTags.RecoveryKey.TITLE).assertTextEquals("Your Recovery Key")
        compose.onNodeWithTag(TestTags.RecoveryKey.BODY).assertTextEquals(string(CoreR.string.recovery_key_body))
        RECOVERY_WORDS.forEachIndexed { index, expected ->
            compose.onNodeWithTag(word(index + 1)).performScrollTo().assertIsDisplayed().assertTextEquals(expected)
        }
        assertFalse(compose.exists(word(0)))
        assertFalse(compose.exists(word(25)))
    }

    @Test
    fun eachWordIsNumbered() {
        show(RecoveryKeyState(words = RECOVERY_WORDS))
        for (position in 1..24) {
            compose.onNode(hasText(position.toString()), useUnmergedTree = true).performScrollTo().assertExists()
        }
    }

    @Test
    fun theWordsSitInTwoColumns() {
        show(RecoveryKeyState(words = RECOVERY_WORDS))
        val first = compose.onNodeWithTag(word(1)).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(word(2)).fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag(word(3)).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(first.top - second.top) < 1f)
        assertTrue(second.left > first.right)
        assertTrue(abs(first.left - third.left) < 1f)
        assertTrue(third.top >= first.bottom)
    }

    @Test
    fun writtenDownReportsTheTap() {
        show(RecoveryKeyState(words = RECOVERY_WORDS))
        compose.onNodeWithTag(TestTags.RecoveryKey.DONE).performScrollTo().assertTextEquals("I've Written It Down").performClick()
        assertEquals(listOf("done"), log)
    }

    @Test
    fun theConfirmStepAsksForEachPositionByNumber() {
        show(confirming)
        compose.onNodeWithTag(TestTags.RecoveryKey.CONFIRM_TITLE).assertTextEquals("Confirm Your Recovery Key")
        compose.onNodeWithTag(label(0)).assertTextEquals("Word 5")
        compose.onNodeWithTag(label(1)).assertTextEquals("Word 10")
        compose.onNodeWithTag(label(2)).performScrollTo().assertTextEquals("Word 23")
        assertFalse(compose.exists(word(1)))
        assertFalse(compose.exists(TestTags.RecoveryKey.DONE))
        assertFalse(compose.exists(TestTags.RecoveryKey.CONFIRM_WRONG))
    }

    @Test
    fun theConfirmFieldsShowAndReportWhatIsTyped() {
        var state by mutableStateOf(confirming)
        compose.show {
            RecoveryKeyContent(
                state = state,
                onWrittenDown = {},
                onTypedChange = { index, value -> state = state.copy(typed = state.typed.toMutableList().also { it[index] = value }) },
                onConfirm = {},
            )
        }
        compose.onNodeWithTag(field(0)).assert(hasEditableText("absent"))
        compose.onNodeWithTag(field(1)).assert(hasEditableText("")).performTextInput("access")
        compose.onNodeWithTag(field(2)).performScrollTo().performTextInput("actress")
        assertEquals(listOf("absent", "access", "actress"), state.typed)
        compose.onNodeWithTag(field(1)).assert(hasEditableText("access"))
    }

    @Test
    fun theKeyboardMovesToTheNextWordAndTheLastOneConfirms() {
        show(confirming)
        compose.onNodeWithTag(field(0)).performClick().performImeAction()
        compose.onNodeWithTag(field(1)).assertIsFocused().performImeAction()
        compose.onNodeWithTag(field(2)).assertIsFocused().performImeAction()
        assertEquals(listOf("confirm"), log)
    }

    @Test
    fun confirmReportsTheTapAndAMismatchShowsTheWarning() {
        show(confirming.copy(mismatch = true))
        compose.onNodeWithTag(TestTags.RecoveryKey.CONFIRM_WRONG).assertTextEquals("That doesn't match. Check your written copy.")
        compose.onNodeWithTag(TestTags.RecoveryKey.CONFIRM_SUBMIT).performScrollTo().assertTextEquals("Confirm").performClick()
        assertEquals(listOf("confirm"), log)
    }

    @Test
    fun confirmIsDisabledWhileBusy() {
        show(confirming.copy(busy = true))
        compose.onNodeWithTag(TestTags.RecoveryKey.CONFIRM_SUBMIT).performScrollTo().assertIsNotEnabled()
    }
}
