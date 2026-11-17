package dev.kodakiko.ui.feature.sharing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.publicKeyFingerprint
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h800dp-xhdpi")
class ShareWithContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<ShareWithAction>()

    private fun idle(username: String = "", found: FoundRecipient? = null, recipients: List<ShareDto> = emptyList()) =
        ShareWithState(username = username, found = found, recipients = recipients, stopping = null, busy = false, error = null)

    private val state = mutableStateOf(idle())

    private fun render(initial: ShareWithState) {
        state.value = initial
        compose.show { ShareWithContent(state = state.value, onAction = { actions += it }) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun theFormHasAUsernameFieldAndFindIsDisabledUntilSomethingIsTyped() {
        render(idle())
        assertTrue(compose.exists(TestTags.Share.SCREEN))
        assertTrue(compose.exists(TestTags.Shared.BACK))
        compose.onNodeWithTag(TestTags.Share.FIND).assert(labeled("Find")).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Share.USERNAME).performTextReplacement("bob")
        compose.waitForIdle()
        assertEquals(listOf<ShareWithAction>(ShareWithAction.UsernameChanged("bob")), actions)
        state.value = idle(username = "bob")
        compose.waitForIdle()
        click(TestTags.Share.FIND)
        assertEquals(ShareWithAction.Find, actions.last())
        assertFalse(compose.exists(TestTags.Share.SECURITY_CODE))
        assertFalse(compose.exists(TestTags.Share.CONFIRM))
    }

    @Test
    fun theKeyboardSearchActionFindsToo() {
        render(idle(username = "bob"))
        compose.onNodeWithTag(TestTags.Share.USERNAME).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf<ShareWithAction>(ShareWithAction.Find), actions)
    }

    @Test
    fun theSecurityCodeIsTheRecipientsKeyFingerprintInFourGroupsExactlyAsTheCoreFormatsIt() {
        val sodium = createSodium()
        val code = publicKeyFingerprint(sodium, sodium.boxKeypair().publicKey)
        assertTrue(code, Regex("^[0-9a-f]{4}( [0-9a-f]{4}){3}$").matches(code))
        render(idle(username = "bob", found = FoundRecipient("bob", code)))
        compose.onNodeWithTag(TestTags.Share.SECURITY_CODE, useUnmergedTree = true).assertTextEquals(code)
        compose.onNodeWithTag(TestTags.Share.SCREEN).assert(contains("bob"))
        compose.onNodeWithTag(TestTags.Share.SCREEN).assert(
            contains("Ask bob to open Settings › Account and read you their security code. If it matches, nobody else can read what you share."),
        )
    }

    @Test
    fun shareAndCancelActOnTheFoundPerson() {
        render(idle(username = "bob", found = FoundRecipient("bob", FINGERPRINT)))
        compose.onNodeWithTag(TestTags.Share.CONFIRM).assert(labeled("Share")).assertIsEnabled()
        compose.onNodeWithTag(TestTags.Share.CANCEL).assert(labeled("Cancel"))
        click(TestTags.Share.CONFIRM)
        click(TestTags.Share.CANCEL)
        assertEquals(listOf(ShareWithAction.ConfirmShare, ShareWithAction.CancelShare), actions)
    }

    @Test
    fun aBusyShareCannotBeTappedAgain() {
        render(idle(username = "bob", found = FoundRecipient("bob", FINGERPRINT)).copy(busy = true))
        compose.onNodeWithTag(TestTags.Share.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun theSharedWithListHasARemoveButtonPerPerson() {
        render(idle(recipients = listOf(shareDto(BOB_ID, "bob"), shareDto(CAROL_ID, "carol"))))
        compose.onNodeWithTag(TestTags.Share.SCREEN).assert(contains("Shared with"))
        compose.onNodeWithTag(listItem(TestTags.Share.RECIPIENT_ROW, 0)).assert(contains("bob"))
        compose.onNodeWithTag(listItem(TestTags.Share.RECIPIENT_ROW, 1)).assert(contains("carol"))
        compose.onNodeWithTag(listItem(TestTags.Share.REMOVE, 1)).assert(labeled("Remove"))
        click(listItem(TestTags.Share.REMOVE, 1))
        assertEquals(listOf<ShareWithAction>(ShareWithAction.Remove(shareDto(CAROL_ID, "carol"))), actions)
    }

    @Test
    fun stoppingAsksWithAWarningAboutCopies() {
        render(idle(recipients = listOf(shareDto(BOB_ID, "bob"))).copy(stopping = shareDto(BOB_ID, "bob")))
        assertTrue(compose.exists(TestTags.Collection.SHEET))
        compose.onNodeWithTag(TestTags.Collection.SHEET).assert(contains("Stop sharing with bob? They may have kept copies of what they already saw."))
        compose.onNodeWithTag(TestTags.Share.STOP_CONFIRM).assert(labeled("Stop Sharing"))
        compose.onNodeWithTag(TestTags.Share.STOP_CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals(listOf<ShareWithAction>(ShareWithAction.ConfirmStop), actions)
    }

    @Test
    fun errorsShowTheirTypedText() {
        render(idle(username = "nobody").copy(error = SharingMessage.UserNotFound))
        compose.onNodeWithTag(TestTags.Shared.ERROR, useUnmergedTree = true).assert(contains("No user with that username on this server."))
        state.value = idle(username = "bob", found = FoundRecipient("bob", FINGERPRINT)).copy(error = SharingMessage.AlreadyShared("bob"))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Shared.ERROR, useUnmergedTree = true).assert(contains("Already shared with bob."))
    }

    @Test
    fun backIsAnAction() {
        render(idle())
        compose.onNodeWithTag(TestTags.Shared.BACK).performClick()
        compose.waitForIdle()
        assertEquals(listOf<ShareWithAction>(ShareWithAction.Back), actions)
    }
}
