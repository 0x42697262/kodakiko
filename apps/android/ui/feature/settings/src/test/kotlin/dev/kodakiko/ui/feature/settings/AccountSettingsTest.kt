@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val USED_BYTES = 340 * 1024L

@RunWith(RobolectricTestRunner::class)
class AccountSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: AccountSettingsViewModel, val events: MutableList<SettingsEvent>) {
        val state: AccountSettingsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = AccountSettingsViewModel(h.accountDeps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun aServerAccountShowsTheUsernameTheHostTheStorageUseAndTheSecurityCode() = runTest {
        val o = open()
        assertEquals(USERNAME, o.state.username)
        assertFalse(o.state.local)
        assertEquals("kodakiko.example.com", o.state.host)
        assertEquals(AccountStorageUse(usedBytes = USED_BYTES, quotaBytes = 10 * GB, asOf = null), o.state.storage)
        assertEquals(SECURITY_CODE, o.state.securityCode)
        assertFalse(o.state.changingServer)
        assertNull(o.state.recovery)
        assertTrue(h.profile.calls.contains(FakeSettingsProfile.REFRESH))
    }

    @Test
    fun theHostKeepsANonDefaultPortAndIsEmptyWithoutAServerAddress() = runTest {
        h.profile.url = "http://127.0.0.1:8080/"
        assertEquals("127.0.0.1:8080", open().state.host)
        h.profile.url = null
        assertEquals("", open().state.host)
    }

    @Test
    fun theUsersOwnQuotaWinsOverTheServerDefaultAndTheV031FallbackIsUsedWithoutConfig() = runTest {
        h.profile.refreshResult = userDto(quotaBytes = 2 * GB, usedBytes = USED_BYTES)
        assertEquals(AccountStorageUse(USED_BYTES, 2 * GB, null), open().state.storage)
        h.profile.refreshResult = userDto(quotaBytes = null, usedBytes = USED_BYTES)
        h.config.config = null
        assertEquals(AccountStorageUse(USED_BYTES, DEFAULT_QUOTA_FALLBACK, null), open().state.storage)
    }

    @Test
    fun withoutAKnownUserThereIsNoStorageLine() = runTest {
        h.profile.userState.value = null
        h.profile.refreshResult = null
        assertNull(open().state.storage)
    }

    @Test
    fun aRefreshThatFailsAfterASuccessShowsTheTimeOfTheLastSuccessInUtc() = runTest {
        val o = open()
        assertNull(o.state.storage?.asOf)
        h.nowMs = NOW_MS + 3_600_000L
        h.profile.refreshFailure = NetworkError()
        o.vm.onAction(AccountSettingsAction.Refresh)
        runCurrent()
        assertEquals(NOW_TEXT, o.state.storage?.asOf)
        h.profile.refreshFailure = null
        h.profile.refreshResult = null
        o.vm.onAction(AccountSettingsAction.Refresh)
        runCurrent()
        assertEquals(NOW_TEXT, o.state.storage?.asOf)
        h.profile.refreshResult = userDto()
        o.vm.onAction(AccountSettingsAction.Refresh)
        runCurrent()
        assertNull(o.state.storage?.asOf)
    }

    @Test
    fun aFirstRefreshThatFailsShowsNoTime() = runTest {
        h.profile.refreshFailure = NetworkError()
        val o = open()
        assertEquals(AccountStorageUse(USED_BYTES, 10 * GB, null), o.state.storage)
    }

    @Test
    fun aLocalJournalNeverAsksTheServerAndHasNoSecurityCodeOrStorageLine() = runTest {
        h.account.modeState.value = AccountMode.LOCAL
        val o = open()
        assertTrue(o.state.local)
        assertNull(o.state.storage)
        assertEquals("", o.state.securityCode)
        assertFalse(h.profile.calls.contains(FakeSettingsProfile.REFRESH))
        assertFalse(h.profile.calls.contains(FakeSettingsProfile.SECURITY_CODE_CALL))
        o.vm.onAction(AccountSettingsAction.Refresh)
        runCurrent()
        assertFalse(h.profile.calls.contains(FakeSettingsProfile.REFRESH))
    }

    @Test
    fun theRowsOpenTheirScreens() = runTest {
        val o = open()
        for (route in listOf(Route.SettingsServer, Route.SettingsPassword, Route.SettingsDeleteAccount, Route.SettingsConnect)) {
            o.vm.onAction(AccountSettingsAction.Open(route))
        }
        runCurrent()
        assertEquals(
            listOf(Route.SettingsServer, Route.SettingsPassword, Route.SettingsDeleteAccount, Route.SettingsConnect).map { SettingsEvent.Open(it) },
            o.events,
        )
    }

    @Test
    fun changeServerAsksWithTheUnsyncedCountAndCancelChangesNothing() = runTest {
        h.sync.statusState.value = idleStatus(queued = 3)
        val o = open()
        o.vm.onAction(AccountSettingsAction.AskChangeServer)
        runCurrent()
        assertTrue(o.state.changingServer)
        assertEquals(3, o.state.unsynced)
        o.vm.onAction(AccountSettingsAction.DismissChangeServer)
        runCurrent()
        assertFalse(o.state.changingServer)
        assertEquals(emptyList<String>(), h.credentials.calls)
        assertEquals(emptyList<SettingsEvent>(), o.events)
    }

    @Test
    fun confirmingChangeServerSignsOutAndReturnsToWelcome() = runTest {
        val o = open()
        o.vm.onAction(AccountSettingsAction.AskChangeServer)
        o.vm.onAction(AccountSettingsAction.ConfirmChangeServer)
        runCurrent()
        assertFalse(o.state.changingServer)
        assertEquals(listOf(FakeSettingsCredentials.CHANGE_SERVER), h.credentials.calls)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.ReplaceAll(Route.Welcome)), o.events)
    }

    @Test
    fun theRecoveryKeyNeedsThePasswordAndAWrongOneKeepsTheSheetWithAnError() = runTest {
        val o = open()
        o.vm.onAction(AccountSettingsAction.ShowRecovery)
        runCurrent()
        assertEquals(RecoverySheet.Prompt(password = "", wrong = false), o.state.recovery)
        o.vm.onAction(AccountSettingsAction.SetRecoveryPassword("not it"))
        o.vm.onAction(AccountSettingsAction.RevealRecovery)
        runCurrent()
        assertEquals(RecoverySheet.Prompt(password = "not it", wrong = true), o.state.recovery)
        o.vm.onAction(AccountSettingsAction.SetRecoveryPassword(GOOD_PASSWORD))
        o.vm.onAction(AccountSettingsAction.RevealRecovery)
        runCurrent()
        assertEquals(RecoverySheet.Words(RECOVERY_WORDS), o.state.recovery)
        assertEquals(listOf("not it", GOOD_PASSWORD), h.credentials.passwords)
        o.vm.onAction(AccountSettingsAction.CloseRecovery)
        runCurrent()
        assertNull(o.state.recovery)
    }

    @Test
    fun anyRevealFailureShowsTheWrongPasswordText() = runTest {
        h.credentials.revealFailure = IllegalStateException("boom")
        val o = open()
        o.vm.onAction(AccountSettingsAction.ShowRecovery)
        o.vm.onAction(AccountSettingsAction.SetRecoveryPassword(GOOD_PASSWORD))
        o.vm.onAction(AccountSettingsAction.RevealRecovery)
        runCurrent()
        assertEquals(RecoverySheet.Prompt(password = GOOD_PASSWORD, wrong = true), o.state.recovery)
    }

    @Test
    fun lockingDropsTheShownWords() = runTest {
        val o = open()
        o.vm.onAction(AccountSettingsAction.ShowRecovery)
        o.vm.onAction(AccountSettingsAction.SetRecoveryPassword(GOOD_PASSWORD))
        o.vm.onAction(AccountSettingsAction.RevealRecovery)
        runCurrent()
        assertEquals(RecoverySheet.Words(RECOVERY_WORDS), o.state.recovery)
        h.profile.unlockedState.value = false
        runCurrent()
        assertEquals(RecoverySheet.Prompt(password = "", wrong = false), o.state.recovery)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(AccountSettingsAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h1600dp-mdpi")
class AccountSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsAccount))

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun open() {
        compose.showWith(navigator) { AccountSettingsScreen(viewModel = AccountSettingsViewModel(h.accountDeps)) }
        await(TestTags.Settings.ACCOUNT_SCREEN)
        await(TestTags.Settings.USERNAME)
    }

    private fun assertInOrder(tags: List<String>) {
        for (tag in tags) assertTrue(tag, compose.exists(tag))
        val tops = tags.map { compose.topOf(it) }
        assertEquals(tags.toString(), tops.sorted(), tops)
    }

    private fun valueOf(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

    @Test
    fun aServerAccountShowsTheV031RowsInOrder() {
        open()
        await(TestTags.Settings.STORAGE_USE)
        assertTrue(compose.showsText(text(R.string.settings_account_title)))
        assertInOrder(
            listOf(
                TestTags.Settings.USERNAME,
                TestTags.Settings.SERVER,
                TestTags.Settings.STORAGE_USE,
                TestTags.Settings.CHANGE_SERVER,
                TestTags.Settings.SECURITY_CODE,
                TestTags.Settings.ROW_SERVER,
                TestTags.Settings.ROW_PASSWORD,
                TestTags.Settings.ROW_RECOVERY,
                TestTags.Settings.ROW_DELETE_ACCOUNT,
            ),
        )
        assertFalse(compose.exists(TestTags.Settings.LOCAL_ONLY))
        assertFalse(compose.exists(TestTags.Settings.ROW_CONNECT))
        assertFalse(compose.exists(TestTags.Settings.CHANGE_SERVER_SHEET))
        assertFalse(compose.exists(TestTags.Settings.RECOVERY_SHEET))
    }

    @Test
    fun theValuesAreTheV031Texts() {
        open()
        await(TestTags.Settings.STORAGE_USE)
        compose.onNodeWithTag(TestTags.Settings.USERNAME, useUnmergedTree = true).assertTextEquals(USERNAME)
        compose.onNodeWithTag(TestTags.Settings.SERVER, useUnmergedTree = true).assertTextEquals("kodakiko.example.com")
        compose.onNodeWithTag(TestTags.Settings.STORAGE_USE, useUnmergedTree = true)
            .assertTextEquals(text(R.string.settings_storage_use, formatBytes(USED_BYTES), formatBytes(10 * GB)))
        compose.onNodeWithTag(TestTags.Settings.SECURITY_CODE, useUnmergedTree = true).assertTextEquals(SECURITY_CODE)
        assertTrue(compose.showsText(text(R.string.settings_username)))
        assertTrue(compose.showsText(text(R.string.settings_server)))
        assertTrue(compose.showsText(text(R.string.settings_security_code)))
        assertTrue(compose.showsText(text(R.string.settings_security_code_others)))
        compose.onNodeWithTag(TestTags.Settings.CHANGE_SERVER).assert(labeled(text(R.string.settings_change_server)))
        compose.onNodeWithTag(TestTags.Settings.ROW_SERVER).assert(labeled(text(R.string.server_title)))
        compose.onNodeWithTag(TestTags.Settings.ROW_PASSWORD).assert(labeled(text(R.string.settings_change_password)))
        compose.onNodeWithTag(TestTags.Settings.ROW_RECOVERY).assert(labeled(text(R.string.settings_show_recovery)))
        compose.onNodeWithTag(TestTags.Settings.ROW_DELETE_ACCOUNT).assert(labeled(text(R.string.settings_delete_account)))
    }

    @Test
    fun aStaleUsageSaysAsOfTheLastRefresh() {
        val vm = AccountSettingsViewModel(h.accountDeps)
        compose.showWith(navigator) { AccountSettingsScreen(viewModel = vm) }
        await(TestTags.Settings.STORAGE_USE)
        h.profile.refreshFailure = NetworkError()
        vm.onAction(AccountSettingsAction.Refresh)
        val expected = text(R.string.settings_storage_use, formatBytes(USED_BYTES), formatBytes(10 * GB)) + " (" + text(R.string.settings_as_of, NOW_TEXT) + ")"
        compose.waitUntil(WAIT_MS) { valueOf(TestTags.Settings.STORAGE_USE) == expected }
    }

    @Test
    fun aLocalJournalOffersConnectAndEraseInsteadOfTheServerRows() {
        h.account.modeState.value = AccountMode.LOCAL
        open()
        assertInOrder(
            listOf(
                TestTags.Settings.USERNAME,
                TestTags.Settings.LOCAL_ONLY,
                TestTags.Settings.ROW_CONNECT,
                TestTags.Settings.ROW_SERVER,
                TestTags.Settings.ROW_PASSWORD,
                TestTags.Settings.ROW_RECOVERY,
                TestTags.Settings.ROW_DELETE_ACCOUNT,
            ),
        )
        compose.onNodeWithTag(TestTags.Settings.LOCAL_ONLY, useUnmergedTree = true).assert(contains(text(R.string.settings_local_only)))
        compose.onNodeWithTag(TestTags.Settings.ROW_CONNECT).assert(labeled(text(R.string.settings_connect)))
        compose.onNodeWithTag(TestTags.Settings.ROW_DELETE_ACCOUNT).assert(labeled(text(R.string.settings_erase)))
        for (tag in listOf(TestTags.Settings.SERVER, TestTags.Settings.STORAGE_USE, TestTags.Settings.CHANGE_SERVER, TestTags.Settings.SECURITY_CODE)) {
            assertFalse(tag, compose.exists(tag))
        }
    }

    @Test
    fun eachRowOpensItsScreen() {
        h.account.modeState.value = AccountMode.LOCAL
        open()
        for (tag in listOf(TestTags.Settings.ROW_CONNECT, TestTags.Settings.ROW_SERVER, TestTags.Settings.ROW_PASSWORD, TestTags.Settings.ROW_DELETE_ACCOUNT)) click(tag)
        compose.waitUntil(WAIT_MS) { navigator.pushed.size == 4 }
        assertEquals(listOf(Route.SettingsConnect, Route.SettingsServer, Route.SettingsPassword, Route.SettingsDeleteAccount), navigator.pushed)
    }

    @Test
    fun changeServerFollowsU21AndAsksWithTheUnsyncedWarning() {
        h.sync.statusState.value = idleStatus(queued = 2)
        open()
        click(TestTags.Settings.CHANGE_SERVER)
        await(TestTags.Settings.CHANGE_SERVER_SHEET)
        assertTrue(compose.showsText(text(R.string.settings_change_server_title)))
        assertTrue(compose.showsText(plural(R.plurals.settings_change_server_unsynced, 2, 2)))
        compose.onNodeWithTag(TestTags.Settings.CHANGE_SERVER_CONFIRM).assert(labeled(text(R.string.settings_change_server_action)))
        compose.onNodeWithTag(TestTags.Settings.CANCEL).assert(labeled(text(R.string.settings_cancel)))
        click(TestTags.Settings.CHANGE_SERVER_CONFIRM)
        compose.waitUntil(WAIT_MS) { navigator.replacedAll.isNotEmpty() }
        assertEquals(listOf(FakeSettingsCredentials.CHANGE_SERVER), h.credentials.calls)
        assertEquals(listOf<Route>(Route.Welcome), navigator.replacedAll)
        assertFalse(compose.exists(TestTags.Settings.CHANGE_SERVER_SHEET))
    }

    @Test
    fun withNothingQueuedThereIsNoWarningAndCancelKeepsTheAccount() {
        open()
        click(TestTags.Settings.CHANGE_SERVER)
        await(TestTags.Settings.CHANGE_SERVER_SHEET)
        assertFalse(compose.showsText(plural(R.plurals.settings_change_server_unsynced, 1, 1)))
        click(TestTags.Settings.CANCEL)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Settings.CHANGE_SERVER_SHEET) }
        assertEquals(emptyList<String>(), h.credentials.calls)
        assertEquals(emptyList<Route>(), navigator.replacedAll)
    }

    @Test
    fun showRecoveryKeyAsksForThePasswordThenListsTheWordsWithTheirIds() {
        open()
        click(TestTags.Settings.ROW_RECOVERY)
        await(TestTags.Settings.RECOVERY_SHEET)
        assertTrue(compose.showsText(text(R.string.settings_recovery_prompt)))
        compose.onNodeWithTag(TestTags.Settings.RECOVERY_SHOW).assert(labeled(text(R.string.settings_recovery_show)))
        compose.onNodeWithTag(TestTags.Settings.RECOVERY_PASSWORD).performTextInput("not it")
        click(TestTags.Settings.RECOVERY_SHOW)
        await(TestTags.Settings.ERROR)
        compose.onNodeWithTag(TestTags.Settings.ERROR).assert(contains(text(R.string.settings_password_wrong)))
        compose.onNodeWithTag(TestTags.Settings.RECOVERY_PASSWORD).performTextClearance()
        compose.onNodeWithTag(TestTags.Settings.RECOVERY_PASSWORD).performTextInput(GOOD_PASSWORD)
        click(TestTags.Settings.RECOVERY_SHOW)
        await(TestTags.Settings.RECOVERY_WORDS)
        compose.onNodeWithTag(TestTags.Settings.RECOVERY_WORDS, useUnmergedTree = true)
            .assertTextEquals(RECOVERY_WORDS.mapIndexed { index, word -> "${index + 1}. $word" }.joinToString("\n"))
        for ((index, word) in RECOVERY_WORDS.withIndex()) {
            compose.onNodeWithTag(listItem(TestTags.Settings.RECOVERY_WORD, index + 1), useUnmergedTree = true).assertTextEquals(word)
        }
        assertFalse(compose.exists(TestTags.Settings.RECOVERY_PASSWORD))
        compose.onNodeWithTag(TestTags.Settings.RECOVERY_DONE).assert(labeled(text(R.string.settings_recovery_done)))
        click(TestTags.Settings.RECOVERY_DONE)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Settings.RECOVERY_SHEET) }
        assertFalse(compose.exists(TestTags.Settings.RECOVERY_WORDS))
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        open()
        click(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
