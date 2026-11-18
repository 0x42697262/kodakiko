package dev.kodakiko.app.navigation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import dev.kodakiko.app.FakeKodakikoServer
import dev.kodakiko.app.ServerFixture
import dev.kodakiko.app.ServerV031App
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.contract.Routes
import dev.kodakiko.data.secure.GlobalStore
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.format.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AccountWiringTest : AppWiringHarness() {
    private fun field(tag: String): SemanticsNodeInteraction {
        awaitTag(tag)
        return compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun openAccount() {
        unlockToGallery()
        tap(TestTags.Tabs.MORE)
        click(TestTags.More.SETTINGS)
        click(TestTags.Settings.ROW_ACCOUNT)
        awaitTag(TestTags.Settings.ACCOUNT_SCREEN)
    }

    private fun unlockWith(password: String) {
        compose.waitUntil(UNLOCK_WAIT_MS) { exists(TestTags.Lock.PASSWORD) || exists(TestTags.Lock.UNLOCK_PASSWORD) }
        if (!exists(TestTags.Lock.PASSWORD)) tap(TestTags.Lock.UNLOCK_PASSWORD)
        field(TestTags.Lock.PASSWORD).performTextClearance()
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performTextInput(password)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
    }

    @Test
    @Config(application = ServerV031App::class)
    fun theU21PathChangesServerAndLandsOnWelcomeWithAnEmptyServerField() {
        openAccount()
        click(TestTags.Settings.CHANGE_SERVER)
        tap(TestTags.Settings.CHANGE_SERVER_CONFIRM)
        awaitTag(TestTags.Welcome.SERVER_LABEL, UNLOCK_WAIT_MS)
        compose.onNodeWithTag(TestTags.Welcome.SERVER_LABEL).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(AccountState.NoServer, app.graph.accountState.value)
        assertNull(GlobalStore(app).serverUrl())
        assertFalse(exists(TestTags.Settings.ACCOUNT_SCREEN))
    }

    @Test
    @Config(application = ServerV031App::class)
    fun aServerAccountShowsTheHostTheUsageFromTheServerAndTheSecurityCode() {
        openAccount()
        val host = ServerFixture.requireServer().baseUrl.removePrefix("http://")
        awaitTag(TestTags.Settings.STORAGE_USE, SYNC_WAIT_MS)
        compose.onNodeWithTag(TestTags.Settings.USERNAME, useUnmergedTree = true).assertTextEquals(V031Fixture.USERNAME)
        compose.onNodeWithTag(TestTags.Settings.SERVER, useUnmergedTree = true).assertTextEquals(host)
        compose.onNodeWithTag(TestTags.Settings.STORAGE_USE, useUnmergedTree = true)
            .assertTextEquals(app.getString(R.string.settings_storage_use, formatBytes(FakeKodakikoServer.ME_USED_BYTES), formatBytes(FakeKodakikoServer.config(true).defaultQuotaBytes)))
        awaitTag(TestTags.Settings.SECURITY_CODE)
        val code = compose.onNodeWithTag(TestTags.Settings.SECURITY_CODE, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text]
        assertTrue(code.joinToString("").isNotBlank())
        assertTrue(ServerFixture.requireServer().count(FakeKodakikoServer.GET, Routes.usersMe()) >= 1)
    }

    @Test
    @Config(application = ServerV031App::class)
    fun unlockingAServerAccountRefreshesTheUserWithoutOpeningTheAccountScreen() {
        unlockToGallery()
        compose.waitUntil(SYNC_WAIT_MS) { ServerFixture.requireServer().count(FakeKodakikoServer.GET, Routes.usersMe()) >= 1 }
        assertFalse(exists(TestTags.Settings.ACCOUNT_SCREEN))
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun changingThePasswordOfALocalJournalMakesOnlyTheNewOneUnlockIt() {
        openAccount()
        click(TestTags.Settings.ROW_PASSWORD)
        awaitTag(TestTags.Settings.PASSWORD_SCREEN)
        field(TestTags.Settings.CURRENT_PASSWORD).performTextInput(V031Fixture.PASSWORD)
        field(TestTags.Settings.NEW_PASSWORD).performTextInput(NEW_PASSWORD)
        field(TestTags.Settings.CONFIRM_PASSWORD).performTextInput(NEW_PASSWORD)
        click(TestTags.Settings.PASSWORD_SUBMIT)
        awaitTag(TestTags.Settings.PASSWORD_RESULT, UNLOCK_WAIT_MS)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_RESULT).assert(contains(app.getString(R.string.settings_password_changed)))
        tap(TestTags.Settings.BACK)
        tap(TestTags.Settings.BACK)
        awaitTag(TestTags.Settings.SCREEN)
        click(TestTags.Settings.ROW_LOCK_NOW)
        awaitTag(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
        unlockWith(V031Fixture.PASSWORD)
        awaitTag(TestTags.Lock.WRONG_PASSWORD, UNLOCK_WAIT_MS)
        assertEquals(AccountState.Locked, app.graph.accountState.value)
        unlockWith(NEW_PASSWORD)
        compose.waitUntil(UNLOCK_WAIT_MS) { app.graph.accountState.value is AccountState.Unlocked }
        awaitGone(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun aWrongCurrentPasswordChangesNothing() {
        openAccount()
        click(TestTags.Settings.ROW_PASSWORD)
        field(TestTags.Settings.CURRENT_PASSWORD).performTextInput("not the password at all")
        field(TestTags.Settings.NEW_PASSWORD).performTextInput(NEW_PASSWORD)
        field(TestTags.Settings.CONFIRM_PASSWORD).performTextInput(NEW_PASSWORD)
        click(TestTags.Settings.PASSWORD_SUBMIT)
        awaitTag(TestTags.Settings.PASSWORD_RESULT, UNLOCK_WAIT_MS)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_RESULT).assert(contains(app.getString(R.string.settings_password_wrong)))
        tap(TestTags.Settings.BACK)
        tap(TestTags.Settings.BACK)
        click(TestTags.Settings.ROW_LOCK_NOW)
        awaitTag(TestTags.Lock.SCREEN, UNLOCK_WAIT_MS)
        unlockWith(V031Fixture.PASSWORD)
        compose.waitUntil(UNLOCK_WAIT_MS) { app.graph.accountState.value is AccountState.Unlocked }
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun showRecoveryKeyOfALocalJournalListsItsTwentyFourWords() {
        openAccount()
        click(TestTags.Settings.ROW_RECOVERY)
        field(TestTags.Settings.RECOVERY_PASSWORD).performTextInput(V031Fixture.PASSWORD)
        tap(TestTags.Settings.RECOVERY_SHOW)
        awaitTag(listItem(TestTags.Settings.RECOVERY_WORD, RECOVERY_WORDS), UNLOCK_WAIT_MS)
        assertFalse(exists(listItem(TestTags.Settings.RECOVERY_WORD, RECOVERY_WORDS + 1)))
        for (position in 1..RECOVERY_WORDS) {
            val word = compose.onNodeWithTag(listItem(TestTags.Settings.RECOVERY_WORD, position), useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString("")
            assertTrue(position.toString(), word.matches(Regex("[a-z]+")))
        }
        tap(TestTags.Settings.RECOVERY_DONE)
        awaitGone(TestTags.Settings.RECOVERY_SHEET)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun serverAndKeysOfALocalJournalShowsTheStoredAccount() {
        openAccount()
        click(TestTags.Settings.ROW_SERVER)
        awaitTag(TestTags.Server.SCREEN)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(contains(FakeKodakikoServer.ME_ID), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        for (text in listOf(V031Fixture.USERNAME, "Argon2id, 16 passes, 256 MB", app.getString(R.string.server_recovery_confirmed))) {
            assertTrue(text, compose.onAllNodes(contains(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        assertTrue(compose.onAllNodes(contains(app.getString(R.string.server_address)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun connectAServerOpensTheConnectScreen() {
        openAccount()
        click(TestTags.Settings.ROW_CONNECT)
        awaitTag(TestTags.Connect.SCREEN)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun erasingTheLocalJournalNeedsItsUsernameAndPasswordThenDeletesIt() {
        assertTrue(V031Fixture.databaseFile(app).exists())
        openAccount()
        click(TestTags.Settings.ROW_DELETE_ACCOUNT)
        awaitTag(TestTags.Settings.DELETE_SCREEN)
        field(TestTags.Settings.DELETE_USERNAME).performTextInput(V031Fixture.USERNAME)
        field(TestTags.Settings.DELETE_PASSWORD).performTextInput(V031Fixture.PASSWORD)
        click(TestTags.Settings.DELETE_CONFIRM)
        awaitTag(TestTags.Welcome.SCREEN, UNLOCK_WAIT_MS)
        compose.waitUntil(WAIT_MS) { GlobalStore(app).activeAccount() == null }
        assertEquals(AccountState.NoServer, app.graph.accountState.value)
        assertFalse(V031Fixture.databaseFile(app).exists())
    }

    private companion object {
        const val NEW_PASSWORD = "a brand new passphrase"
        const val RECOVERY_WORDS = 24
    }
}
