@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.PreferenceHighlighted
import dev.kodakiko.ui.core.settings.SettingsIndexEntry
import dev.kodakiko.ui.core.settings.searchSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

private class ExpectedSection(@StringRes val title: Int, val route: Route, val items: List<Int>)

private val V031_INDEX = listOf(
    ExpectedSection(
        R.string.prefs_appearance,
        Route.SettingsAppearance,
        listOf(
            R.string.prefs_theme,
            R.string.prefs_date_format,
            R.string.prefs_relative_dates,
            R.string.prefs_start_screen,
            R.string.prefs_full_screen,
            R.string.prefs_hide_bars_on_scroll,
            R.string.prefs_pure_black,
            R.string.prefs_time_format,
            R.string.prefs_haptics,
            R.string.prefs_dynamic_color,
        ),
    ),
    ExpectedSection(
        R.string.advanced_title,
        Route.SettingsAdvanced,
        listOf(R.string.prefs_technical_details, R.string.advanced_rebuild, R.string.advanced_reset, R.string.advanced_debug),
    ),
    ExpectedSection(
        R.string.prefs_gallery,
        Route.SettingsGallery,
        listOf(
            R.string.prefs_display_mode,
            R.string.prefs_columns_portrait,
            R.string.prefs_columns_landscape,
            R.string.prefs_group_by_day,
            R.string.prefs_badges,
            R.string.prefs_show_tag_tabs,
            R.string.prefs_show_counts,
            R.string.prefs_on_this_day,
            R.string.prefs_confirm_trash,
            R.string.prefs_hide_shared,
        ),
    ),
    ExpectedSection(
        R.string.prefs_capture,
        Route.SettingsCapture,
        listOf(
            R.string.settings_location,
            R.string.prefs_start_camera,
            R.string.prefs_keep_screen_on,
            R.string.prefs_camera_app,
            R.string.prefs_hold_to_record,
            R.string.prefs_hold_max,
        ),
    ),
    ExpectedSection(
        R.string.prefs_security,
        Route.SettingsSecurity,
        listOf(
            R.string.settings_auto_lock,
            R.string.settings_biometric,
            R.string.settings_encrypt_thumbnails,
            R.string.prefs_secure_screen,
            R.string.prefs_hide_note_previews,
        ),
    ),
    ExpectedSection(
        R.string.prefs_data_storage,
        Route.SettingsStorage,
        listOf(
            R.string.settings_cache_cap,
            R.string.settings_clear_originals,
            R.string.settings_wifi_only,
            R.string.settings_large_means,
            R.string.prefs_export_journal,
            R.string.prefs_autoplay,
            R.string.prefs_autoplay_mobile,
        ),
    ),
    ExpectedSection(R.string.settings_row_sync, Route.SettingsSync, listOf(R.string.settings_sync_now, R.string.settings_retry_failed)),
    ExpectedSection(
        R.string.settings_row_account,
        Route.SettingsAccount,
        listOf(
            R.string.settings_change_password,
            R.string.settings_show_recovery,
            R.string.settings_delete_account,
            R.string.settings_security_code,
            R.string.server_title,
        ),
    ),
    ExpectedSection(R.string.settings_row_about, Route.SettingsAbout, listOf(R.string.settings_app_version, R.string.settings_licenses)),
)

@RunWith(RobolectricTestRunner::class)
class SettingsSearchIndexTest {
    private val sections by lazy { settingsSearchSections(resources) }

    @Test
    fun theSearchIndexIsTheV031IndexWithoutTheWebOnlyKeepUnlockedRow() {
        assertEquals(V031_INDEX.map { text(it.title) }, sections.map { it.title })
        assertEquals(V031_INDEX.map { it.route }, sections.map { it.route })
        for ((expected, actual) in V031_INDEX.zip(sections)) {
            assertEquals(text(expected.title), expected.items.map { text(it) }, actual.preferences.map { it.title })
            assertTrue(actual.preferences.all { it.visible })
        }
        assertEquals(V031_INDEX.sumOf { it.items.size }, sections.sumOf { it.preferences.size })
    }

    @Test
    fun autoLockIsFoundInSecurityAndPrivacyAndCarriesItsRowTitleAsTheHighlightKey() {
        val results = searchSettings(sections, "auto-lock")
        assertEquals(1, results.size)
        val result = results.single()
        assertEquals(Route.SettingsSecurity, result.route)
        assertEquals(text(R.string.settings_auto_lock), result.highlightKey)
        assertEquals(text(R.string.prefs_security), result.breadcrumbs)
    }

    @Test
    fun aSectionHeadingResultOpensTheScreenWithoutAHighlight() {
        val heading = searchSettings(sections, "sync").first()
        assertEquals(Route.SettingsSync, heading.route)
        assertEquals(text(R.string.settings_row_sync), heading.title)
        assertNull(heading.highlightKey)
    }
}

class SettingsHighlightTest {
    @Test
    fun aRequestedHighlightIsTakenOnceByItsScreen() {
        val highlight = SettingsHighlight()
        highlight.request(Route.SettingsSecurity, "Auto-lock")
        assertEquals("Auto-lock", highlight.take(Route.SettingsSecurity))
        assertNull(highlight.take(Route.SettingsSecurity))
    }

    @Test
    fun anotherScreenDoesNotGetTheHighlight() {
        val highlight = SettingsHighlight()
        highlight.request(Route.SettingsSecurity, "Auto-lock")
        assertNull(highlight.take(Route.SettingsAppearance))
    }

    @Test
    fun aHeadingResultRequestsNoHighlight() {
        val highlight = SettingsHighlight()
        highlight.request(Route.SettingsSync, null)
        assertNull(highlight.take(Route.SettingsSync))
        assertNull(SettingsHighlight().take(Route.SettingsSync))
    }
}

@RunWith(RobolectricTestRunner::class)
class SettingsSearchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private fun entry(title: String, route: Route, highlightKey: String?): SettingsIndexEntry =
        SettingsIndexEntry(title = title, section = "Section", route = route, breadcrumbs = "Section", highlightKey = highlightKey)

    @Test
    fun openingAResultReplacesTheSearchWithItsScreenAndLeavesTheHighlightForIt() = runTest {
        val vm = SettingsSearchViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onAction(SettingsSearchAction.Open(entry("Auto-lock", Route.SettingsSecurity, "Auto-lock")))
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Replace(Route.SettingsSecurity)), events)
        assertEquals("Auto-lock", h.highlight.take(Route.SettingsSecurity))
    }

    @Test
    fun backLeavesTheSearch() = runTest {
        val vm = SettingsSearchViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        vm.onAction(SettingsSearchAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), events)
    }

    @Test
    fun aScreenOpenedFromASearchResultStartsWithItsHighlightOnlyOnce() {
        h.highlight.request(Route.SettingsSecurity, text(R.string.settings_auto_lock))
        assertEquals(text(R.string.settings_auto_lock), SecuritySettingsViewModel(h.deps).highlightKey)
        assertNull(SecuritySettingsViewModel(h.deps).highlightKey)
        h.highlight.request(Route.SettingsAppearance, text(R.string.prefs_theme))
        assertEquals(text(R.string.prefs_theme), AppearanceSettingsViewModel(h.deps).highlightKey)
        h.highlight.request(Route.SettingsGallery, text(R.string.prefs_display_mode))
        assertEquals(text(R.string.prefs_display_mode), GallerySettingsViewModel(h.deps).highlightKey)
        h.highlight.request(Route.SettingsCapture, text(R.string.prefs_hold_max))
        assertEquals(text(R.string.prefs_hold_max), CaptureSettingsViewModel(h.deps).highlightKey)
        h.highlight.request(Route.SettingsStorage, text(R.string.settings_cache_cap))
        assertEquals(text(R.string.settings_cache_cap), StorageSettingsViewModel(h.deps).highlightKey)
        h.highlight.request(Route.SettingsSync, text(R.string.settings_retry_failed))
        assertEquals(text(R.string.settings_retry_failed), SyncSettingsViewModel(h.deps).highlightKey)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SettingsSearchFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()

    private fun highlighted(value: Boolean): SemanticsMatcher = SemanticsMatcher.expectValue(PreferenceHighlighted, value)

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    @Test
    fun searchingAutoLockOpensSecurityAndPrivacyWithTheAutoLockRowHighlighted() {
        val host = SettingsTestHost(h, listOf(Route.Gallery, Route.Settings))
        compose.show { host.Content() }
        await(TestTags.Settings.SCREEN)
        compose.onNodeWithTag(TestTags.Settings.SEARCH).performClick()
        await(TestTags.Settings.SEARCH_FIELD)
        assertEquals(listOf<Route>(Route.SettingsSearch), host.pushed)
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput("auto-lock")
        await(listItem(TestTags.Settings.RESULT, 0))
        compose.onNodeWithTag(listItem(TestTags.Settings.RESULT, 0)).assert(contains(text(R.string.settings_auto_lock)))
        compose.onNodeWithTag(listItem(TestTags.Settings.RESULT, 0)).assert(contains(text(R.string.prefs_security)))
        assertFalse(compose.exists(listItem(TestTags.Settings.RESULT, 1)))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(listItem(TestTags.Settings.RESULT, 0)).performClick()
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        assertTrue(compose.exists(TestTags.Settings.SECURITY_SCREEN))
        assertEquals(listOf<Route>(Route.SettingsSecurity), host.replaced)
        assertEquals(listOf(Route.Gallery, Route.Settings, Route.SettingsSecurity), host.routes)
        compose.onNodeWithTag(TestTags.Settings.AUTO_LOCK).assert(highlighted(true))
        compose.onNodeWithTag(TestTags.Settings.HIDE_NOTE_PREVIEWS).assert(highlighted(false))
        compose.mainClock.advanceTimeBy(HIGHLIGHT_OVER_AT_MS)
        compose.onNodeWithTag(TestTags.Settings.AUTO_LOCK).assert(highlighted(false))
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun openingSecurityFromTheRootRowHighlightsNothing() {
        val host = SettingsTestHost(h, listOf(Route.Settings))
        compose.show { host.Content() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(TestTags.Settings.ROW_SECURITY).performClick()
        compose.mainClock.advanceTimeBy(HIGHLIGHT_VISIBLE_AT_MS)
        assertTrue(compose.exists(TestTags.Settings.SECURITY_SCREEN))
        compose.onNodeWithTag(TestTags.Settings.AUTO_LOCK).assert(highlighted(false))
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun theUpArrowOfTheSearchReturnsToTheSettingsRoot() {
        val host = SettingsTestHost(h, listOf(Route.Settings, Route.SettingsSearch))
        compose.show { host.Content() }
        await(TestTags.Settings.SEARCH_FIELD)
        compose.onNode(hasContentDescription(text(R.string.settings_back))).performClick()
        await(TestTags.Settings.SCREEN)
        assertEquals(listOf<Route>(Route.Settings), host.routes)
        assertEquals(1, host.pops)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val HIGHLIGHT_VISIBLE_AT_MS = 1_200L
        const val HIGHLIGHT_OVER_AT_MS = 5_000L
    }
}
