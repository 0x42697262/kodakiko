@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
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

private val ALL_ROWS = listOf(
    SettingsRow.APPEARANCE,
    SettingsRow.GALLERY,
    SettingsRow.TAGS,
    SettingsRow.CAPTURE,
    SettingsRow.SYNC,
    SettingsRow.STORAGE,
    SettingsRow.SECURITY,
    SettingsRow.ACCOUNT,
    SettingsRow.LINKS,
    SettingsRow.ADMIN,
    SettingsRow.ADVANCED,
    SettingsRow.ABOUT,
    SettingsRow.LOCK_NOW,
    SettingsRow.SIGN_OUT,
)

class SettingsRowsTest {
    @Test
    fun aServerAdminWithPublicLinksSeesAllFourteenRowsInTheV031Order() {
        assertEquals(ALL_ROWS, settingsRows(local = false, publicLinksEnabled = true, admin = true))
        assertEquals(14, SettingsRow.entries.size)
        assertEquals(ALL_ROWS, SettingsRow.entries.toList())
    }

    @Test
    fun publicLinksAndAdminRowsFollowTheConfigAndTheRole() {
        assertEquals(ALL_ROWS - SettingsRow.LINKS - SettingsRow.ADMIN, settingsRows(local = false, publicLinksEnabled = false, admin = false))
        assertEquals(ALL_ROWS - SettingsRow.ADMIN, settingsRows(local = false, publicLinksEnabled = true, admin = false))
        assertEquals(ALL_ROWS - SettingsRow.LINKS, settingsRows(local = false, publicLinksEnabled = false, admin = true))
    }

    @Test
    fun aLocalJournalHasNoLinksNoAdminAndNoSignOutEvenWhenTheFlagsSayYes() {
        val local = ALL_ROWS - SettingsRow.LINKS - SettingsRow.ADMIN - SettingsRow.SIGN_OUT
        assertEquals(local, settingsRows(local = true, publicLinksEnabled = true, admin = true))
        assertEquals(local, settingsRows(local = true, publicLinksEnabled = false, admin = false))
        assertEquals(11, local.size)
    }

    @Test
    fun everyRowCarriesItsV031TestIdAndTheScreenItOpens() {
        val expected = mapOf(
            SettingsRow.APPEARANCE to (TestTags.Settings.ROW_APPEARANCE to Route.SettingsAppearance),
            SettingsRow.GALLERY to (TestTags.Settings.ROW_GALLERY to Route.SettingsGallery),
            SettingsRow.TAGS to (TestTags.Settings.ROW_TAGS to Route.Tags),
            SettingsRow.CAPTURE to (TestTags.Settings.ROW_CAPTURE to Route.SettingsCapture),
            SettingsRow.SYNC to (TestTags.Settings.ROW_SYNC to Route.SettingsSync),
            SettingsRow.STORAGE to (TestTags.Settings.ROW_STORAGE to Route.SettingsStorage),
            SettingsRow.SECURITY to (TestTags.Settings.ROW_SECURITY to Route.SettingsSecurity),
            SettingsRow.ACCOUNT to (TestTags.Settings.ROW_ACCOUNT to Route.SettingsAccount),
            SettingsRow.LINKS to (TestTags.Settings.ROW_LINKS to Route.SettingsLinks),
            SettingsRow.ADMIN to (TestTags.Settings.ROW_ADMIN to Route.SettingsAdmin),
            SettingsRow.ADVANCED to (TestTags.Settings.ROW_ADVANCED to Route.SettingsAdvanced),
            SettingsRow.ABOUT to (TestTags.Settings.ROW_ABOUT to Route.SettingsAbout),
            SettingsRow.LOCK_NOW to (TestTags.Settings.ROW_LOCK_NOW to null),
            SettingsRow.SIGN_OUT to (TestTags.Settings.ROW_SIGN_OUT to null),
        )
        for (row in SettingsRow.entries) assertEquals(row.name, expected.getValue(row), row.testTag to row.route)
    }
}

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: SettingsViewModel, val events: MutableList<SettingsEvent>) {
        val state: SettingsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = SettingsViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun anAdminOnAServerWithPublicLinksGetsEveryRow() = runTest {
        h.account.admin = true
        val o = open()
        assertEquals(ALL_ROWS, o.state.rows)
        assertFalse(o.state.confirmingSignOut)
        assertTrue(h.account.adminCalls >= 1)
    }

    @Test
    fun aFailedAdminCheckCountsAsNotAdmin() = runTest {
        h.account.admin = true
        h.account.adminFailure = NetworkError()
        assertEquals(ALL_ROWS - SettingsRow.ADMIN, open().state.rows)
    }

    @Test
    fun theLinksRowNeedsPublicLinksInTheServerConfig() = runTest {
        h.config.config = publicConfig(links = false)
        assertEquals(ALL_ROWS - SettingsRow.LINKS - SettingsRow.ADMIN, open().state.rows)
    }

    @Test
    fun noKnownConfigHidesTheLinksRow() = runTest {
        h.config.config = null
        assertEquals(ALL_ROWS - SettingsRow.LINKS - SettingsRow.ADMIN, open().state.rows)
    }

    @Test
    fun aLocalJournalHidesLinksAdminAndSignOutAndFollowsTheAccountMode() = runTest {
        h.account.admin = true
        h.account.modeState.value = AccountMode.LOCAL
        val o = open()
        assertEquals(ALL_ROWS - SettingsRow.LINKS - SettingsRow.ADMIN - SettingsRow.SIGN_OUT, o.state.rows)
        h.account.modeState.value = AccountMode.SERVER
        runCurrent()
        assertEquals(ALL_ROWS, o.state.rows)
    }

    @Test
    fun rowsWithAScreenOpenItAndTagsOpensTheTagEditor() = runTest {
        h.account.admin = true
        val o = open()
        for (row in ALL_ROWS - SettingsRow.LOCK_NOW - SettingsRow.SIGN_OUT) o.vm.onAction(SettingsAction.Open(row))
        runCurrent()
        assertEquals(
            listOf(
                Route.SettingsAppearance,
                Route.SettingsGallery,
                Route.Tags,
                Route.SettingsCapture,
                Route.SettingsSync,
                Route.SettingsStorage,
                Route.SettingsSecurity,
                Route.SettingsAccount,
                Route.SettingsLinks,
                Route.SettingsAdmin,
                Route.SettingsAdvanced,
                Route.SettingsAbout,
            ).map { SettingsEvent.Open(it) },
            o.events,
        )
    }

    @Test
    fun lockNowLocksTheJournalWithoutNavigating() = runTest {
        val o = open()
        o.vm.onAction(SettingsAction.Open(SettingsRow.LOCK_NOW))
        runCurrent()
        assertEquals(1, h.account.locks)
        assertEquals(emptyList<SettingsEvent>(), o.events)
    }

    @Test
    fun signOutWithNothingWaitingSignsOutAtOnce() = runTest {
        val o = open()
        o.vm.onAction(SettingsAction.Open(SettingsRow.SIGN_OUT))
        runCurrent()
        assertEquals(1, h.account.signOuts)
        assertFalse(o.state.confirmingSignOut)
    }

    @Test
    fun signOutWithUnsyncedChangesAsksFirstAndConfirmSignsOut() = runTest {
        h.sync.statusState.value = idleStatus(queued = 3)
        val o = open()
        o.vm.onAction(SettingsAction.Open(SettingsRow.SIGN_OUT))
        runCurrent()
        assertTrue(o.state.confirmingSignOut)
        assertEquals(0, h.account.signOuts)
        o.vm.onAction(SettingsAction.ConfirmSignOut)
        runCurrent()
        assertFalse(o.state.confirmingSignOut)
        assertEquals(1, h.account.signOuts)
    }

    @Test
    fun dismissingTheSignOutQuestionKeepsTheAccount() = runTest {
        h.sync.statusState.value = idleStatus(queued = 1)
        val o = open()
        o.vm.onAction(SettingsAction.Open(SettingsRow.SIGN_OUT))
        runCurrent()
        o.vm.onAction(SettingsAction.DismissSignOut)
        runCurrent()
        assertFalse(o.state.confirmingSignOut)
        assertEquals(0, h.account.signOuts)
    }

    @Test
    fun deadItemsAloneDoNotAskBeforeSigningOut() = runTest {
        h.sync.statusState.value = idleStatus(dead = 2)
        val o = open()
        o.vm.onAction(SettingsAction.Open(SettingsRow.SIGN_OUT))
        runCurrent()
        assertEquals(1, h.account.signOuts)
    }

    @Test
    fun searchOpensTheSettingsSearchScreenAndBackLeaves() = runTest {
        val o = open()
        o.vm.onAction(SettingsAction.Search)
        o.vm.onAction(SettingsAction.Back)
        runCurrent()
        assertEquals(listOf(SettingsEvent.Open(Route.SettingsSearch), SettingsEvent.Back), o.events)
    }

    @Test
    fun theAdminCheckRunsOffTheFirstFrameAndTheRowAppearsWhenItAnswers() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val slow = object : SettingsAccount by h.account {
            override suspend fun isAdmin(): Boolean = gate.await()
        }
        val deps = h.deps
        val vm = SettingsViewModel(
            SettingsDeps(
                settings = deps.settings,
                account = slow,
                config = deps.config,
                sync = deps.sync,
                storage = deps.storage,
                thumbnails = deps.thumbnails,
                biometric = deps.biometric,
                location = deps.location,
                backup = deps.backup,
                highlight = deps.highlight,
                dynamicColorAvailable = deps.dynamicColorAvailable,
            ),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        assertEquals(ALL_ROWS - SettingsRow.ADMIN, vm.state.value.rows)
        gate.complete(true)
        runCurrent()
        assertEquals(ALL_ROWS, vm.state.value.rows)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class SettingsContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SettingsAction>()

    private fun show(rows: List<SettingsRow> = ALL_ROWS, confirmingSignOut: Boolean = false) {
        compose.show { SettingsContent(state = SettingsState(rows = rows, confirmingSignOut = confirmingSignOut), onAction = { actions += it }) }
    }

    private val titles = mapOf(
        SettingsRow.APPEARANCE to R.string.prefs_appearance,
        SettingsRow.GALLERY to R.string.prefs_gallery,
        SettingsRow.TAGS to R.string.more_tags,
        SettingsRow.CAPTURE to R.string.prefs_capture,
        SettingsRow.SYNC to R.string.settings_row_sync,
        SettingsRow.STORAGE to R.string.prefs_data_storage,
        SettingsRow.SECURITY to R.string.prefs_security,
        SettingsRow.ACCOUNT to R.string.settings_row_account,
        SettingsRow.LINKS to R.string.settings_row_links,
        SettingsRow.ADMIN to R.string.settings_row_admin,
        SettingsRow.ADVANCED to R.string.advanced_title,
        SettingsRow.ABOUT to R.string.settings_row_about,
        SettingsRow.LOCK_NOW to R.string.settings_lock_now,
        SettingsRow.SIGN_OUT to R.string.settings_sign_out,
    )

    private val summaries = mapOf(
        SettingsRow.APPEARANCE to R.string.prefs_appearance_summary,
        SettingsRow.GALLERY to R.string.prefs_gallery_summary,
        SettingsRow.TAGS to R.string.tags_settings_summary,
        SettingsRow.CAPTURE to R.string.prefs_capture_summary,
        SettingsRow.SYNC to R.string.prefs_sync_summary,
        SettingsRow.STORAGE to R.string.prefs_data_storage_summary,
        SettingsRow.SECURITY to R.string.prefs_security_summary,
        SettingsRow.ACCOUNT to R.string.prefs_account_summary,
        SettingsRow.LINKS to R.string.prefs_links_summary,
        SettingsRow.ADMIN to R.string.prefs_admin_summary,
        SettingsRow.ADVANCED to R.string.advanced_summary,
        SettingsRow.ABOUT to R.string.prefs_about_summary,
    )

    @Test
    fun everyRowShowsItsTitleAndSummaryUnderTheV031IdInOrder() {
        show()
        assertTrue(compose.exists(TestTags.Settings.SCREEN))
        assertTrue(compose.showsText(text(R.string.settings_title)))
        for (row in ALL_ROWS) {
            val node = compose.onNodeWithTag(row.testTag).performScrollTo()
            node.assert(contains(text(titles.getValue(row))))
            summaries[row]?.let { node.assert(contains(text(it))) }
        }
        val tops = ALL_ROWS.map { compose.topOf(it.testTag) }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun onlyTheRowsInTheStateAreShown() {
        show(rows = ALL_ROWS - SettingsRow.LINKS - SettingsRow.ADMIN - SettingsRow.SIGN_OUT)
        assertFalse(compose.exists(TestTags.Settings.ROW_LINKS))
        assertFalse(compose.exists(TestTags.Settings.ROW_ADMIN))
        assertFalse(compose.exists(TestTags.Settings.ROW_SIGN_OUT))
        assertTrue(compose.exists(TestTags.Settings.ROW_LOCK_NOW))
    }

    @Test
    fun tappingARowAsksToOpenIt() {
        show()
        for (row in ALL_ROWS) {
            compose.onNodeWithTag(row.testTag).performScrollTo().performClick()
            compose.waitForIdle()
        }
        assertEquals(ALL_ROWS.map { SettingsAction.Open(it) }, actions)
    }

    @Test
    fun theSearchActionAndTheBackArrowReportTheirActions() {
        show()
        compose.onNodeWithTag(TestTags.Settings.SEARCH).performClick()
        compose.onNodeWithTag(TestTags.Settings.BACK).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SettingsAction.Search, SettingsAction.Back), actions)
    }

    @Test
    fun theSignOutQuestionWarnsAboutUnsyncedChangesAndOffersSignOutAndCancel() {
        show(confirmingSignOut = true)
        assertTrue(compose.exists(TestTags.Settings.SIGN_OUT_SHEET))
        assertTrue(compose.showsText(text(R.string.settings_sign_out_confirm)))
        compose.onNodeWithTag(TestTags.Settings.SIGN_OUT_CONFIRM).assert(labeled(text(R.string.settings_sign_out))).performClick()
        compose.onNodeWithTag(TestTags.Settings.CANCEL).assert(labeled(text(R.string.settings_cancel))).performClick()
        compose.waitForIdle()
        assertEquals(listOf(SettingsAction.ConfirmSignOut, SettingsAction.DismissSignOut), actions)
    }

    @Test
    fun withoutTheQuestionThereIsNoSignOutSheet() {
        show()
        assertFalse(compose.exists(TestTags.Settings.SIGN_OUT_SHEET))
        assertNull(actions.firstOrNull())
    }
}
