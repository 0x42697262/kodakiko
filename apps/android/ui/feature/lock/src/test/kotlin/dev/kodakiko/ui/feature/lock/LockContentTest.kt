@file:OptIn(ExperimentalTestApi::class)

package dev.kodakiko.ui.feature.lock

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
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
class LockContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private fun show(state: LockState = LockState(username = "alice")) {
        compose.show {
            LockContent(
                state = state,
                onUnlockWithDevice = { log += "device" },
                onUnlockWithPassword = { log += "password" },
                onPasswordChange = { log += "typed:$it" },
                onForgot = { log += "forgot" },
                onSignOut = { log += "signOut" },
                onSignOutConfirm = { log += "confirm" },
                onDismissDialog = { log += "dismiss" },
            )
        }
    }

    @Test
    fun showsTheTitleTheLockedUsernameAndTheThreeActions() {
        show()
        compose.onNodeWithTag(TestTags.Lock.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.Lock.TITLE).assertIsDisplayed().assertTextEquals("Journal Locked")
        compose.onNodeWithTag(TestTags.Lock.LOCKED_AS).assertTextEquals("Locked — alice")
        compose.onNodeWithTag(TestTags.Lock.UNLOCK_DEVICE).performScrollTo().assertTextEquals("Unlock").assertIsEnabled()
        compose.onNodeWithTag(TestTags.Lock.UNLOCK_PASSWORD).performScrollTo().assertTextEquals("Unlock with Password")
        compose.onNodeWithTag(TestTags.Lock.FORGOT).performScrollTo().assertTextEquals("Forgot password?")
        compose.onNodeWithTag(TestTags.Lock.SIGN_OUT).performScrollTo().assertTextEquals("Sign Out")
        assertFalse(compose.exists(TestTags.Lock.PASSWORD))
        assertFalse(compose.exists(TestTags.Lock.WRONG_PASSWORD))
        assertFalse(compose.exists(TestTags.Lock.UPDATED))
        assertFalse(compose.exists(TestTags.Lock.UNREADABLE))
        assertFalse(compose.exists(TestTags.Lock.SIGN_OUT_SHEET))
    }

    @Test
    fun withoutAUsernameTheLockedAsLineIsHidden() {
        show(LockState(username = null))
        assertFalse(compose.exists(TestTags.Lock.LOCKED_AS))
    }

    @Test
    fun theButtonsReportTheirTaps() {
        show()
        compose.onNodeWithTag(TestTags.Lock.UNLOCK_DEVICE).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.Lock.UNLOCK_PASSWORD).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.Lock.FORGOT).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.Lock.SIGN_OUT).performScrollTo().performClick()
        assertEquals(listOf("device", "password", "forgot", "signOut"), log)
    }

    @Test
    fun theShownPasswordFieldIsHiddenTextAndReportsTyping() {
        show(LockState(username = "alice", passwordShown = true))
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().assert(isPassword).performTextInput(GOOD_PASSWORD)
        assertEquals(listOf("typed:$GOOD_PASSWORD"), log)
    }

    @Test
    fun enterOnThePasswordFieldUnlocksAsMaestroPressesIt() {
        show(LockState(username = "alice", passwordShown = true, password = GOOD_PASSWORD))
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(listOf("password"), log)
    }

    @Test
    fun theKeyboardActionOfThePasswordFieldUnlocks() {
        show(LockState(username = "alice", passwordShown = true, password = GOOD_PASSWORD))
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performImeAction()
        assertEquals(listOf("password"), log)
    }

    @Test
    fun aWrongPasswordIsSaidUnderTheField() {
        show(LockState(username = "alice", passwordShown = true, wrongPassword = true))
        compose.onNodeWithTag(TestTags.Lock.WRONG_PASSWORD).performScrollTo().assertTextEquals("That password is incorrect.")
        assertFalse(compose.exists(TestTags.Errors.WRONG_PASSWORD))
    }

    @Test
    fun otherErrorsShowABanner() {
        show(LockState(username = "alice", passwordShown = true, error = errorBanner(AuthError(AuthFailure.INVALID_CREDENTIALS))))
        compose.onNodeWithTag(TestTags.Errors.INVALID_CREDENTIALS).performScrollTo().assertTextEquals("Username or password is incorrect.")
    }

    @Test
    fun unlockIsDisabledWhileBusy() {
        show(LockState(username = "alice", busy = true))
        compose.onNodeWithTag(TestTags.Lock.UNLOCK_DEVICE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theUpdatedNoticeAsksForThePasswordOnce() {
        show(LockState(username = "k007local", passwordShown = true, updatedNotice = true))
        compose.onNodeWithTag(TestTags.Lock.UPDATED).performScrollTo().assertTextEquals("Kodakiko was updated. Enter your password to unlock.")
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnreadableJournalSaysSoAndOffersNoUnlock() {
        show(LockState(username = "k007local", unreadable = true))
        compose.onNodeWithTag(TestTags.Lock.UNREADABLE).performScrollTo().assertTextEquals("Your journal could not be opened.")
        assertFalse(compose.exists(TestTags.Lock.UNLOCK_DEVICE))
        assertFalse(compose.exists(TestTags.Lock.UNLOCK_PASSWORD))
        assertFalse(compose.exists(TestTags.Lock.PASSWORD))
    }

    @Test
    fun theSignOutQuestionConfirmsOrCancels() {
        show(LockState(username = "alice", dialog = LockDialog.SignOut))
        compose.onNodeWithTag(TestTags.Lock.SIGN_OUT_SHEET).assertExists()
        compose.onNode(hasText("Sign out? Unsynced changes on this device will be lost.")).assertExists()
        compose.onNodeWithTag(TestTags.Lock.SIGN_OUT_CONFIRM).assertTextEquals("Sign Out").performClick()
        compose.onNodeWithTag(TestTags.Lock.CANCEL).assertTextEquals("Cancel").performClick()
        assertEquals(listOf("confirm", "dismiss"), log)
    }
}
