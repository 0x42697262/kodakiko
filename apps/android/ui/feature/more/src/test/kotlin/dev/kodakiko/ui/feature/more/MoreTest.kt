@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.more

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class MoreRowTest {
    @Test
    fun theRowsFollowTheV031OrderWithTheirIdsAndRoutes() {
        assertEquals(
            listOf(
                Triple(MoreRow.LOCK_NOW, TestTags.More.LOCK_NOW, null),
                Triple(MoreRow.TAGS, TestTags.More.TAGS, Route.Tags),
                Triple(MoreRow.STATISTICS, TestTags.More.STATISTICS, Route.Statistics),
                Triple(MoreRow.TRASH, TestTags.More.TRASH, Route.Trash),
                Triple(MoreRow.DATA_STORAGE, TestTags.More.DATA_STORAGE, Route.SettingsStorage),
                Triple(MoreRow.SETTINGS, TestTags.More.SETTINGS, Route.Settings),
                Triple(MoreRow.ABOUT, TestTags.More.ABOUT, Route.SettingsAbout),
                Triple(MoreRow.HELP, TestTags.More.HELP, null),
            ),
            MoreRow.entries.map { Triple(it, it.testTag, it.route) },
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class MoreViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = MoreHarness()

    private class Opened(val vm: MoreViewModel, val events: MutableList<MoreEvent>) {
        val state: MoreState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = MoreViewModel(h.deps)
        val events = mutableListOf<MoreEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun theStateFollowsTheSettingsAndTheAccountMode() = runTest {
        val o = open()
        assertEquals(MoreState(hideShared = false, syncPaused = false, local = false), o.state)
        h.settings.set(defaults.copy(hideShared = true, syncPaused = true))
        h.account.modeState.value = AccountMode.LOCAL
        runCurrent()
        assertEquals(MoreState(hideShared = true, syncPaused = true, local = true), o.state)
    }

    @Test
    fun hideSharedWritesOnlyItsField() = runTest {
        val o = open()
        o.vm.onAction(MoreAction.SetHideShared(true))
        runCurrent()
        assertEquals(defaults.copy(hideShared = true), h.settings.value)
        assertEquals(0, h.sync.calls)
    }

    @Test
    fun pausingSyncWritesTheSettingWithoutSyncing() = runTest {
        val o = open()
        o.vm.onAction(MoreAction.SetSyncPaused(true))
        runCurrent()
        assertEquals(defaults.copy(syncPaused = true), h.settings.value)
        assertEquals(0, h.sync.calls)
    }

    @Test
    fun resumingSyncWritesTheSettingFirstThenSyncsAtOnce() = runTest {
        h.settings.set(defaults.copy(syncPaused = true))
        val o = open()
        o.vm.onAction(MoreAction.SetSyncPaused(false))
        runCurrent()
        assertEquals(defaults, h.settings.value)
        assertEquals(1, h.sync.calls)
        assertEquals(listOf(false), h.sync.pausedAtSync)
    }

    @Test
    fun aFailedSyncAfterResumingIsIgnored() = runTest {
        h.settings.set(defaults.copy(syncPaused = true))
        h.sync.failure = IllegalStateException("offline")
        val o = open()
        o.vm.onAction(MoreAction.SetSyncPaused(false))
        runCurrent()
        assertEquals(defaults, h.settings.value)
        assertEquals(MoreState(hideShared = false, syncPaused = false, local = false), o.state)
    }

    @Test
    fun theRowsOpenTheirRoutesLockNowLocksAndHelpOpensTheHelpPage() = runTest {
        val o = open()
        for (row in MoreRow.entries) o.vm.onAction(MoreAction.Open(row))
        runCurrent()
        assertEquals(1, h.account.locks)
        assertEquals(
            listOf(Route.Tags, Route.Statistics, Route.Trash, Route.SettingsStorage, Route.Settings, Route.SettingsAbout).map { MoreEvent.Open(it) } + MoreEvent.OpenHelp,
            o.events,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class MoreScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = MoreHarness()
    private val navigator = RecordingNavigator(listOf(Route.More))
    private val uris = RecordingUriHandler()

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun open() {
        compose.showWith(navigator, uris) { MoreScreen(viewModel = MoreViewModel(h.deps)) }
        await(TestTags.More.SCREEN)
        await(TestTags.More.HELP)
    }

    private fun assertInOrder(tags: List<String>) {
        for (tag in tags) assertTrue(tag, compose.exists(tag))
        val tops = tags.map { compose.topOf(it) }
        assertEquals(tags.toString(), tops.sorted(), tops)
    }

    @Test
    fun aServerAccountShowsTheLogoBothSwitchesAndEveryRowInTheV031Order() {
        open()
        assertEquals(1, compose.onAllNodes(hasContentDescription(text(R.string.more_app_name)), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertInOrder(
            listOf(
                TestTags.More.HIDE_SHARED,
                TestTags.More.PAUSE_SYNC,
                TestTags.More.LOCK_NOW,
                TestTags.More.TAGS,
                TestTags.More.STATISTICS,
                TestTags.More.TRASH,
                TestTags.More.DATA_STORAGE,
                TestTags.More.SETTINGS,
                TestTags.More.ABOUT,
                TestTags.More.HELP,
            ),
        )
        val logoTop = compose.onAllNodes(hasContentDescription(text(R.string.more_app_name)), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue(logoTop < compose.topOf(TestTags.More.HIDE_SHARED))
    }

    @Test
    fun theTitlesAndSummariesAreTheV031Texts() {
        open()
        compose.onNodeWithTag(TestTags.More.HIDE_SHARED)
            .assert(contains(text(R.string.more_hide_shared)))
            .assert(contains(text(R.string.more_hide_shared_summary)))
            .assert(unchecked())
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC)
            .assert(contains(text(R.string.more_pause_sync)))
            .assert(contains(text(R.string.more_pause_sync_summary)))
            .assert(unchecked())
        val titles = listOf(
            TestTags.More.LOCK_NOW to R.string.more_lock_now,
            TestTags.More.TAGS to R.string.more_tags,
            TestTags.More.STATISTICS to R.string.more_statistics,
            TestTags.More.TRASH to R.string.more_trash,
            TestTags.More.DATA_STORAGE to R.string.more_data_storage,
            TestTags.More.SETTINGS to R.string.more_settings,
            TestTags.More.ABOUT to R.string.more_about,
            TestTags.More.HELP to R.string.more_help,
        )
        for ((tag, title) in titles) compose.onNodeWithTag(tag).assert(labeled(text(title)))
    }

    @Test
    fun theSwitchesShowTheStoredSettings() {
        h.settings.set(defaults.copy(hideShared = true, syncPaused = true))
        open()
        compose.onNodeWithTag(TestTags.More.HIDE_SHARED).assert(checked())
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC).assert(checked())
        assertEquals(0, h.settings.updates)
        assertEquals(0, h.sync.calls)
    }

    @Test
    fun aLocalJournalHasNoPauseSyncSwitch() {
        h.account.modeState.value = AccountMode.LOCAL
        open()
        assertTrue(compose.exists(TestTags.More.HIDE_SHARED))
        assertFalse(compose.exists(TestTags.More.PAUSE_SYNC))
    }

    @Test
    fun theSwitchesWriteTheirSettingsAndResumingSyncs() {
        open()
        click(TestTags.More.HIDE_SHARED)
        assertEquals(defaults.copy(hideShared = true), h.settings.value)
        compose.onNodeWithTag(TestTags.More.HIDE_SHARED).assert(checked())
        click(TestTags.More.PAUSE_SYNC)
        assertEquals(defaults.copy(hideShared = true, syncPaused = true), h.settings.value)
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC).assert(checked())
        assertEquals(0, h.sync.calls)
        click(TestTags.More.PAUSE_SYNC)
        compose.waitUntil(WAIT_MS) { h.sync.calls == 1 }
        assertEquals(defaults.copy(hideShared = true), h.settings.value)
        compose.onNodeWithTag(TestTags.More.PAUSE_SYNC).assert(unchecked())
    }

    @Test
    fun theRowsOpenTheirScreens() {
        open()
        for (tag in listOf(TestTags.More.TAGS, TestTags.More.STATISTICS, TestTags.More.TRASH, TestTags.More.DATA_STORAGE, TestTags.More.SETTINGS, TestTags.More.ABOUT)) {
            click(tag)
        }
        compose.waitUntil(WAIT_MS) { navigator.pushed.size == 6 }
        assertEquals(listOf(Route.Tags, Route.Statistics, Route.Trash, Route.SettingsStorage, Route.Settings, Route.SettingsAbout), navigator.pushed)
    }

    @Test
    fun lockNowLocksAndHelpOpensTheV031HelpPage() {
        open()
        click(TestTags.More.LOCK_NOW)
        compose.waitUntil(WAIT_MS) { h.account.locks == 1 }
        click(TestTags.More.HELP)
        compose.waitUntil(WAIT_MS) { uris.opened.isNotEmpty() }
        assertEquals(listOf(text(R.string.more_help_url)), uris.opened)
        assertEquals(emptyList<Route>(), navigator.pushed)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class MoreScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, state: MoreState, appTheme: AppTheme = AppTheme.LIGHT) {
        compose.showWith(RecordingNavigator(listOf(Route.More)), appTheme = appTheme) { MoreContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("more/$name")
    }

    @Test
    fun server() {
        capture("server", MoreState(hideShared = false, syncPaused = false, local = false))
    }

    @Test
    fun serverPausedDark() {
        capture("server_paused_dark", MoreState(hideShared = true, syncPaused = true, local = false), AppTheme.DARK)
    }

    @Test
    fun local() {
        capture("local", MoreState(hideShared = false, syncPaused = false, local = true))
    }
}
