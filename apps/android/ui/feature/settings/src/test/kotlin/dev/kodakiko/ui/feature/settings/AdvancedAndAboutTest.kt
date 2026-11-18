@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.PreferenceHighlighted
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

private val changed: DeviceSettings get() = defaults.copy(appTheme = AppTheme.DARK, autoLockMinutes = 1, showTechnicalDetails = true, hideShared = true)

@RunWith(RobolectricTestRunner::class)
class AdvancedSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: AdvancedSettingsViewModel, val events: MutableList<SettingsEvent>) {
        val state: AdvancedSettingsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = AdvancedSettingsViewModel(h.advancedDeps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun theStateCarriesTheSettingsAndNothingElseAtFirst() = runTest {
        val o = open()
        assertEquals(
            AdvancedSettingsState(settings = defaults, debugInfo = null, confirmingReset = false, message = null, showDiagnostics = true),
            o.state,
        )
        h.showDiagnostics = false
        assertFalse(open().state.showDiagnostics)
    }

    @Test
    fun technicalDetailsWritesOnlyItsField() = runTest {
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.SetTechnicalDetails(true))
        runCurrent()
        assertEquals(defaults.copy(showTechnicalDetails = true), h.store.value)
        assertEquals(h.store.value, o.state.settings)
    }

    @Test
    fun rebuildClearsTheIndexBuildsItAgainAndReportsTheV031Numbers() = runTest {
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.Rebuild)
        runCurrent()
        assertEquals(listOf(FakeIndexService.CLEAR, FakeIndexService.BUILD), h.index.calls)
        assertEquals(AdvancedMessage.Rebuilt(entries = 42, durationMs = 137), o.state.message)
        o.vm.onAction(AdvancedSettingsAction.MessageShown)
        runCurrent()
        assertNull(o.state.message)
    }

    @Test
    fun aFailedRebuildSaysNothing() = runTest {
        h.index.failure = IllegalStateException("locked")
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.Rebuild)
        runCurrent()
        assertEquals(listOf(FakeIndexService.CLEAR, FakeIndexService.BUILD), h.index.calls)
        assertNull(o.state.message)
    }

    @Test
    fun debugInfoTogglesTheDiagnosticsJson() = runTest {
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.ToggleDebug)
        runCurrent()
        assertEquals(DEBUG_JSON, o.state.debugInfo)
        o.vm.onAction(AdvancedSettingsAction.ToggleDebug)
        runCurrent()
        assertNull(o.state.debugInfo)
    }

    @Test
    fun resetAsksFirstAndCancelChangesNothing() = runTest {
        h.store.set(changed)
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.AskReset)
        runCurrent()
        assertTrue(o.state.confirmingReset)
        o.vm.onAction(AdvancedSettingsAction.DismissReset)
        runCurrent()
        assertFalse(o.state.confirmingReset)
        assertEquals(changed, h.store.value)
        assertNull(o.state.message)
    }

    @Test
    fun confirmingResetRestoresEveryAndroidDefaultAndSaysSo() = runTest {
        h.store.set(changed)
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.AskReset)
        o.vm.onAction(AdvancedSettingsAction.ConfirmReset)
        runCurrent()
        assertEquals(defaults, h.store.value)
        assertFalse(o.state.confirmingReset)
        assertEquals(AdvancedMessage.Reset, o.state.message)
    }

    @Test
    fun diagnosticsAndBackNavigate() = runTest {
        val o = open()
        o.vm.onAction(AdvancedSettingsAction.OpenDiagnostics)
        o.vm.onAction(AdvancedSettingsAction.Back)
        runCurrent()
        assertEquals(listOf(SettingsEvent.Open(Route.SettingsDiagnostics), SettingsEvent.Back), o.events)
    }

    @Test
    fun aSearchHighlightForAdvancedIsTakenOnce() = runTest {
        h.highlight.request(Route.SettingsAdvanced, text(R.string.advanced_rebuild))
        assertEquals(text(R.string.advanced_rebuild), AdvancedSettingsViewModel(h.advancedDeps).highlightKey)
        assertNull(AdvancedSettingsViewModel(h.advancedDeps).highlightKey)
    }
}

@RunWith(RobolectricTestRunner::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private fun TestScope.open(): DiagnosticsViewModel {
        val vm = DiagnosticsViewModel(h.advancedDeps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun runningTheVectorsShowsRunningThenTheSummaryAndEveryCase() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.diagnostics.gate = gate
        val vm = open()
        assertEquals(DiagnosticsState(running = false, lines = emptyList()), vm.state.value)
        vm.onAction(DiagnosticsAction.RunVectors)
        runCurrent()
        assertEquals(DiagnosticsState(running = true, lines = listOf(DiagnosticsLine.Running)), vm.state.value)
        vm.onAction(DiagnosticsAction.RunVectors)
        runCurrent()
        assertEquals(1, h.diagnostics.runs)
        gate.complete(Unit)
        runCurrent()
        assertEquals(
            DiagnosticsState(
                running = false,
                lines = listOf(
                    DiagnosticsLine.Summary(passed = 2, total = 3),
                    DiagnosticsLine.Case(name = "argon2id basic", passed = true, durationMs = 812),
                    DiagnosticsLine.Case(name = "xchacha20 seal", passed = true, durationMs = 3),
                    DiagnosticsLine.Case(name = "stream final chunk", passed = false, durationMs = 11),
                ),
            ),
            vm.state.value,
        )
    }

    @Test
    fun aFailureIsOneLineWithItsMessage() = runTest {
        h.diagnostics.failure = IllegalStateException("no vectors")
        val vm = open()
        vm.onAction(DiagnosticsAction.RunVectors)
        runCurrent()
        assertEquals(DiagnosticsState(running = false, lines = listOf(DiagnosticsLine.Failed("no vectors"))), vm.state.value)
    }
}

@RunWith(RobolectricTestRunner::class)
class AboutViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private fun TestScope.open(): AboutViewModel {
        val vm = AboutViewModel(h.aboutDeps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun theStateHasTheVersionsAndTheLicensesSortedLikeLocaleCompare() = runTest {
        h.licenses.licenses = FakeSettingsLicenses.DEFAULT_LICENSES + OpenSourceLicense("androidx.core", "1.17.0", "Apache-2.0")
        assertEquals(
            AboutState(
                appVersion = APP_VERSION,
                serverVersion = "0.3.1",
                licenses = listOf(
                    OpenSourceLicense("androidx.core", "1.17.0", "Apache-2.0"),
                    OpenSourceLicense("Coil", "3.3.0", "Apache-2.0"),
                    OpenSourceLicense("OkHttp", "5.3.0", "Apache-2.0"),
                    OpenSourceLicense("Voyager", "2.2.21", "MIT"),
                ),
            ),
            open().state.value,
        )
    }

    @Test
    fun withoutAKnownServerTheServerVersionIsUnknown() = runTest {
        h.config.config = null
        assertNull(open().state.value.serverVersion)
    }

    @Test
    fun anEmptyLicenseListStaysEmpty() = runTest {
        h.licenses.licenses = emptyList()
        assertEquals(emptyList<OpenSourceLicense>(), open().state.value.licenses)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class AdvancedDiagnosticsAboutScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertInOrder(tags: List<String>) {
        for (tag in tags) assertTrue(tag, compose.exists(tag))
        val tops = tags.map { compose.topOf(it) }
        assertEquals(tags.toString(), tops.sorted(), tops)
    }

    private fun openAdvanced(navigator: RecordingNavigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsAdvanced))): RecordingNavigator {
        compose.showWith(navigator) { AdvancedSettingsScreen(viewModel = AdvancedSettingsViewModel(h.advancedDeps)) }
        await(TestTags.Advanced.SCREEN)
        await(TestTags.Advanced.REBUILD)
        return navigator
    }

    @Test
    fun advancedHasTheV031RowsAndTheDiagnosticsRow() {
        openAdvanced()
        assertTrue(compose.showsText(text(R.string.advanced_title)))
        assertInOrder(
            listOf(
                TestTags.Settings.TECHNICAL_DETAILS,
                TestTags.Advanced.REBUILD,
                TestTags.Advanced.RESET,
                TestTags.Advanced.DEBUG,
                TestTags.Advanced.DIAGNOSTICS,
            ),
        )
        compose.onNodeWithTag(TestTags.Settings.TECHNICAL_DETAILS)
            .assert(contains(text(R.string.prefs_technical_details)))
            .assert(contains(text(R.string.prefs_technical_details_summary)))
            .assert(unchecked())
        compose.onNodeWithTag(TestTags.Advanced.REBUILD).assert(contains(text(R.string.advanced_rebuild))).assert(contains(text(R.string.advanced_rebuild_summary)))
        compose.onNodeWithTag(TestTags.Advanced.RESET).assert(contains(text(R.string.advanced_reset))).assert(contains(text(R.string.advanced_reset_summary)))
        compose.onNodeWithTag(TestTags.Advanced.DEBUG).assert(contains(text(R.string.advanced_debug))).assert(contains(text(R.string.advanced_debug_summary)))
        compose.onNodeWithTag(TestTags.Advanced.DIAGNOSTICS).assert(contains(text(R.string.diagnostics_title)))
        assertFalse(compose.exists(TestTags.Advanced.DEBUG_TEXT))
    }

    @Test
    fun withoutDiagnosticsTheRowIsHidden() {
        h.showDiagnostics = false
        openAdvanced()
        assertFalse(compose.exists(TestTags.Advanced.DIAGNOSTICS))
    }

    @Test
    fun technicalDetailsIsASwitch() {
        openAdvanced()
        click(TestTags.Settings.TECHNICAL_DETAILS)
        assertEquals(defaults.copy(showTechnicalDetails = true), h.store.value)
        compose.onNodeWithTag(TestTags.Settings.TECHNICAL_DETAILS).assert(checked())
    }

    @Test
    fun rebuildShowsTheV031Snackbar() {
        openAdvanced()
        click(TestTags.Advanced.REBUILD)
        await(TestTags.Advanced.MESSAGE)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE, useUnmergedTree = true).assertTextEquals(text(R.string.advanced_rebuilt, 42, 137))
        assertEquals(listOf(FakeIndexService.CLEAR, FakeIndexService.BUILD), h.index.calls)
    }

    @Test
    fun resetAsksInASheetAndConfirmingRestoresTheDefaults() {
        h.store.set(changed)
        openAdvanced()
        click(TestTags.Advanced.RESET)
        await(TestTags.Advanced.RESET_SHEET)
        assertTrue(compose.showsText(text(R.string.advanced_reset_confirm)))
        assertEquals(changed, h.store.value)
        compose.onNodeWithTag(TestTags.Advanced.RESET_CONFIRM).assert(labeled(text(R.string.advanced_reset)))
        click(TestTags.Advanced.RESET_CONFIRM)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Advanced.RESET_SHEET) }
        assertEquals(defaults, h.store.value)
        await(TestTags.Advanced.MESSAGE)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE, useUnmergedTree = true).assertTextEquals(text(R.string.advanced_reset_done))
    }

    @Test
    fun cancellingTheResetKeepsTheSettings() {
        h.store.set(changed)
        openAdvanced()
        click(TestTags.Advanced.RESET)
        await(TestTags.Advanced.RESET_SHEET)
        click(TestTags.Settings.CANCEL)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Advanced.RESET_SHEET) }
        assertEquals(changed, h.store.value)
        assertFalse(compose.exists(TestTags.Advanced.MESSAGE))
    }

    @Test
    fun debugInfoShowsAndHidesTheJson() {
        openAdvanced()
        click(TestTags.Advanced.DEBUG)
        await(TestTags.Advanced.DEBUG_TEXT)
        compose.onNodeWithTag(TestTags.Advanced.DEBUG_TEXT, useUnmergedTree = true).assertTextEquals(DEBUG_JSON)
        click(TestTags.Advanced.DEBUG)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Advanced.DEBUG_TEXT) }
    }

    @Test
    fun theDiagnosticsRowAndTheBackArrowNavigate() {
        val navigator = openAdvanced()
        click(TestTags.Advanced.DIAGNOSTICS)
        compose.waitUntil(WAIT_MS) { navigator.pushed.isNotEmpty() }
        assertEquals(listOf<Route>(Route.SettingsDiagnostics), navigator.pushed)
        click(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }

    @Test
    fun searchingRebuildOpensAdvancedWithTheRowHighlighted() {
        val host = SettingsTestHost(h, listOf(Route.Settings))
        compose.show { host.Content() }
        await(TestTags.Settings.SCREEN)
        compose.onNodeWithTag(TestTags.Settings.SEARCH).performClick()
        await(TestTags.Settings.SEARCH_FIELD)
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput("rebuild")
        await(listItem(TestTags.Settings.RESULT, 0))
        assertFalse(compose.exists(listItem(TestTags.Settings.RESULT, 1)))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(listItem(TestTags.Settings.RESULT, 0)).performClick()
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        assertTrue(compose.exists(TestTags.Advanced.SCREEN))
        compose.onNodeWithTag(TestTags.Advanced.REBUILD).assert(SemanticsMatcher.expectValue(PreferenceHighlighted, true))
        compose.onNodeWithTag(TestTags.Advanced.RESET).assert(SemanticsMatcher.expectValue(PreferenceHighlighted, false))
        compose.mainClock.autoAdvance = true
        assertEquals(listOf(Route.Settings, Route.SettingsAdvanced), host.routes)
    }

    @Test
    fun diagnosticsRunsTheVectorsAndListsTheV031Lines() {
        val gate = CompletableDeferred<Unit>()
        h.diagnostics.gate = gate
        compose.showWith(RecordingNavigator(listOf(Route.SettingsAdvanced, Route.SettingsDiagnostics))) {
            DiagnosticsScreen(viewModel = DiagnosticsViewModel(h.advancedDeps))
        }
        await(TestTags.Diagnostics.SCREEN)
        assertTrue(compose.showsText(text(R.string.diagnostics_title)))
        compose.onNodeWithTag(TestTags.Diagnostics.RUN_VECTORS).assert(labeled(text(R.string.diagnostics_run_vectors))).assertIsEnabled()
        assertFalse(compose.exists(listItem(TestTags.Diagnostics.LINE, 0)))
        click(TestTags.Diagnostics.RUN_VECTORS)
        await(listItem(TestTags.Diagnostics.LINE, 0))
        compose.onNodeWithTag(listItem(TestTags.Diagnostics.LINE, 0), useUnmergedTree = true).assertTextEquals(text(R.string.diagnostics_running))
        compose.onNodeWithTag(TestTags.Diagnostics.RUN_VECTORS).assertIsNotEnabled()
        gate.complete(Unit)
        compose.waitUntil(WAIT_MS) { compose.exists(listItem(TestTags.Diagnostics.LINE, 3)) }
        compose.onNodeWithTag(listItem(TestTags.Diagnostics.LINE, 0), useUnmergedTree = true).assertTextEquals("2 of 3 passed")
        compose.onNodeWithTag(listItem(TestTags.Diagnostics.LINE, 1), useUnmergedTree = true).assertTextEquals("PASS argon2id basic (812 ms)")
        compose.onNodeWithTag(listItem(TestTags.Diagnostics.LINE, 2), useUnmergedTree = true).assertTextEquals("PASS xchacha20 seal (3 ms)")
        compose.onNodeWithTag(listItem(TestTags.Diagnostics.LINE, 3), useUnmergedTree = true).assertTextEquals("FAIL stream final chunk (11 ms)")
        assertFalse(compose.exists(listItem(TestTags.Diagnostics.LINE, 4)))
        compose.onNodeWithTag(TestTags.Diagnostics.RUN_VECTORS).assertIsEnabled()
    }

    @Test
    fun aDiagnosticsFailureIsTheV031FailedLine() {
        h.diagnostics.failure = IllegalStateException("no vectors")
        compose.showWith(RecordingNavigator(listOf(Route.SettingsAdvanced, Route.SettingsDiagnostics))) {
            DiagnosticsScreen(viewModel = DiagnosticsViewModel(h.advancedDeps))
        }
        click(TestTags.Diagnostics.RUN_VECTORS)
        compose.waitUntil(WAIT_MS) { compose.showsText("Failed: no vectors") }
        compose.onNodeWithTag(listItem(TestTags.Diagnostics.LINE, 0), useUnmergedTree = true).assertTextEquals(text(R.string.diagnostics_failed, "no vectors"))
    }

    private fun openAbout() {
        compose.showWith(RecordingNavigator(listOf(Route.Settings, Route.SettingsAbout))) { AboutScreen(viewModel = AboutViewModel(h.aboutDeps)) }
        await(TestTags.Settings.ABOUT_SCREEN)
        await(TestTags.Settings.MIHON_ATTRIBUTION)
    }

    @Test
    fun aboutShowsTheVersionsTheSortedLicensesAndTheMihonAttribution() {
        openAbout()
        await(listItem(TestTags.Settings.LICENSE, 2))
        assertTrue(compose.showsText(text(R.string.settings_row_about)))
        compose.onNodeWithTag(TestTags.Settings.APP_VERSION, useUnmergedTree = true).assertTextEquals(APP_VERSION)
        compose.onNodeWithTag(TestTags.Settings.SERVER_VERSION, useUnmergedTree = true).assertTextEquals("0.3.1")
        assertTrue(compose.showsText(text(R.string.settings_app_version)))
        assertTrue(compose.showsText(text(R.string.settings_server_version)))
        assertTrue(compose.showsText(text(R.string.settings_licenses)))
        compose.onNodeWithTag(listItem(TestTags.Settings.LICENSE, 0)).assert(contains("Coil 3.3.0 \u00b7 Apache-2.0"))
        compose.onNodeWithTag(listItem(TestTags.Settings.LICENSE, 1)).assert(contains("OkHttp 5.3.0 \u00b7 Apache-2.0"))
        compose.onNodeWithTag(listItem(TestTags.Settings.LICENSE, 2)).assert(contains("Voyager 2.2.21 \u00b7 MIT"))
        assertFalse(compose.exists(listItem(TestTags.Settings.LICENSE, 3)))
        assertFalse(compose.exists(TestTags.Settings.LICENSES_EMPTY))
        val attribution = compose.onNodeWithTag(TestTags.Settings.MIHON_ATTRIBUTION)
        attribution.assert(contains(text(R.string.about_mihon_attribution)))
        for (part in listOf("Mihon", "https://github.com/mihonapp/mihon", "Apache-2.0", "Javier Tom\u00e1s")) attribution.assert(contains(part))
    }

    @Test
    fun withoutLicensesAboutSaysSoAndStillCreditsMihon() {
        h.licenses.licenses = emptyList()
        h.config.config = null
        openAbout()
        await(TestTags.Settings.LICENSES_EMPTY)
        compose.onNodeWithTag(TestTags.Settings.LICENSES_EMPTY, useUnmergedTree = true).assert(contains(text(R.string.settings_licenses_empty)))
        compose.onNodeWithTag(TestTags.Settings.SERVER_VERSION, useUnmergedTree = true).assertTextEquals("\u2014")
        assertTrue(compose.exists(TestTags.Settings.MIHON_ATTRIBUTION))
        assertFalse(compose.exists(listItem(TestTags.Settings.LICENSE, 0)))
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val HIGHLIGHT_VISIBLE_AT_MS = 1_200L
    }
}
