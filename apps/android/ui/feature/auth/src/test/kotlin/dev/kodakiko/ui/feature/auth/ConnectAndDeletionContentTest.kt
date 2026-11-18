package dev.kodakiko.ui.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
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
class ConnectAndDeletionContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val log = mutableListOf<String>()

    private val ready = ConnectServerState(server = "journal.example.test", username = "k007local", password = GOOD_PASSWORD)

    private fun showConnect(state: ConnectServerState = ConnectServerState()) {
        compose.show {
            ConnectServerContent(
                state = state,
                onServerChange = { log += "server:$it" },
                onInviteChange = { log += "invite:$it" },
                onUsernameChange = { log += "username:$it" },
                onPasswordChange = { log += "password:$it" },
                onConnect = { log += "connect" },
                onBack = { log += "back" },
            )
        }
    }

    private fun showDeletion(state: CancelDeletionState = CancelDeletionState(date = "November 21, 2026")) {
        compose.show {
            CancelDeletionContent(state = state, onKeep = { log += "keep" }, onSignOut = { log += "signOut" })
        }
    }

    @Test
    fun connectShowsTheFormWithTheV031Tags() {
        showConnect(ready)
        compose.onNodeWithTag(TestTags.Connect.SCREEN).assertExists()
        compose.onNode(hasText("Connect a Server")).assertExists()
        compose.onNodeWithTag(TestTags.Connect.SERVER).assert(hasEditableText("journal.example.test")).assert(isPassword.not())
        compose.onNodeWithTag(TestTags.Connect.INVITE).assert(hasEditableText("")).assert(isPassword.not())
        compose.onNodeWithTag(TestTags.Connect.USERNAME).performScrollTo().assert(hasEditableText("k007local"))
        compose.onNodeWithTag(TestTags.Connect.PASSWORD).performScrollTo().assert(isPassword)
        compose.onNodeWithTag(TestTags.Connect.ACTION).performScrollTo().assertTextEquals("Connect").assertIsEnabled()
        assertFalse(compose.exists(TestTags.Connect.DONE))
    }

    @Test
    fun connectFieldsReportTyping() {
        var state by mutableStateOf(ConnectServerState())
        compose.show {
            ConnectServerContent(
                state = state,
                onServerChange = { state = state.copy(server = it) },
                onInviteChange = { state = state.copy(invite = it) },
                onUsernameChange = { state = state.copy(username = it) },
                onPasswordChange = { state = state.copy(password = it) },
                onConnect = { log += "connect" },
                onBack = { log += "back" },
            )
        }
        compose.onNodeWithTag(TestTags.Connect.SERVER).performTextInput(INVITE_LINK)
        compose.onNodeWithTag(TestTags.Connect.INVITE).performTextInput(INVITE_LINK)
        compose.onNodeWithTag(TestTags.Connect.USERNAME).performScrollTo().performTextInput("k007local")
        compose.onNodeWithTag(TestTags.Connect.PASSWORD).performScrollTo().performTextInput(GOOD_PASSWORD)
        assertEquals(ConnectServerState(server = INVITE_LINK, invite = INVITE_LINK, username = "k007local", password = GOOD_PASSWORD), state)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun connectKeyboardMovesDownAndThePasswordConnects() {
        showConnect(ready)
        compose.onNodeWithTag(TestTags.Connect.SERVER).performScrollTo().performClick().performImeAction()
        compose.onNodeWithTag(TestTags.Connect.INVITE).assertIsFocused().performImeAction()
        compose.onNodeWithTag(TestTags.Connect.USERNAME).assertIsFocused().performImeAction()
        compose.onNodeWithTag(TestTags.Connect.PASSWORD).assertIsFocused().performImeAction()
        assertEquals(listOf("connect"), log)
    }

    @Test
    fun connectHasAnUpButton() {
        showConnect()
        compose.onNodeWithTag(TestTags.Connect.BACK).performClick()
        assertEquals(listOf("back"), log)
    }

    @Test
    fun connectReportsTheTap() {
        showConnect(ready)
        compose.onNodeWithTag(TestTags.Connect.ACTION).performScrollTo().performClick()
        assertEquals(listOf("connect"), log)
    }

    @Test
    fun connectIsDisabledWithoutAServerOrPassword() {
        showConnect(ready.copy(password = ""))
        compose.onNodeWithTag(TestTags.Connect.ACTION).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun connectIsDisabledWhileBusy() {
        showConnect(ready.copy(busy = true))
        compose.onNodeWithTag(TestTags.Connect.ACTION).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aConnectedJournalShowsTheDoneNoticeAndCannotConnectAgain() {
        showConnect(ready.copy(password = "", done = true))
        compose.onNodeWithTag(TestTags.Connect.DONE).performScrollTo().assertTextEquals("Connected. Your journal is uploading.")
        compose.onNodeWithTag(TestTags.Connect.ACTION).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun connectShowsErrors() {
        showConnect(ready.copy(error = errorBanner(AuthError(AuthFailure.INVITE_INVALID))))
        compose.onNodeWithTag(TestTags.Errors.INVITE_INVALID).performScrollTo()
            .assertTextEquals("This invite link is invalid, used or expired. Ask for a new one.")
    }

    @Test
    fun cancelDeletionShowsTheDateAndBothActions() {
        showDeletion()
        compose.onNodeWithTag(TestTags.CancelDeletion.SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.CancelDeletion.TITLE).assertTextEquals("Account Scheduled for Deletion")
        compose.onNodeWithTag(TestTags.CancelDeletion.BODY).assertTextEquals("Your journal will be erased on November 21, 2026. You can still keep it.")
        compose.onNodeWithTag(TestTags.CancelDeletion.SUBMIT).performScrollTo().assertTextEquals("Keep My Account").performClick()
        compose.onNodeWithTag(TestTags.CancelDeletion.SIGN_OUT).performScrollTo().assertTextEquals("Sign Out").performClick()
        assertEquals(listOf("keep", "signOut"), log)
    }

    @Test
    fun keepMyAccountIsDisabledWhileBusy() {
        showDeletion(CancelDeletionState(date = "November 21, 2026", busy = true))
        compose.onNodeWithTag(TestTags.CancelDeletion.SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun cancelDeletionShowsErrors() {
        showDeletion(CancelDeletionState(error = errorBanner(AuthError(AuthFailure.UNREACHABLE))))
        compose.onNodeWithTag(TestTags.Errors.UNREACHABLE).performScrollTo()
            .assertTextEquals("Can't reach the server. Check your connection or VPN, then try again.")
    }

    @Test
    fun securingTellsTheUserToWait() {
        compose.show { SecuringContent() }
        compose.onNodeWithTag(TestTags.SignUp.SECURING_SCREEN).assertExists()
        compose.onNodeWithTag(TestTags.SignUp.SECURING).assertTextEquals("Securing your account…")
        compose.onNodeWithTag(TestTags.SignUp.SECURING_DETAIL).assertTextEquals("This takes a few seconds. Keep the app open.")
    }
}
