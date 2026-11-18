package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DateFormat
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.data.db.ThemePreset
import dev.kodakiko.data.db.TimeFormat
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.theme.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

abstract class PreferenceScreenHarness {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    val h = SettingsHarness()

    fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    fun tap(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    fun toggles(tag: String, read: (DeviceSettings) -> Boolean) {
        val before = read(h.store.value)
        compose.onNodeWithTag(tag).performScrollTo().assert(if (before) checked() else unchecked())
        click(tag)
        assertEquals(tag, !before, read(h.store.value))
        compose.onNodeWithTag(tag).performScrollTo().assert(if (before) unchecked() else checked())
        click(tag)
        assertEquals(tag, before, read(h.store.value))
    }

    fun <T> chooses(tag: String, option: Any, expected: T, read: (DeviceSettings) -> T) {
        click(tag)
        await(SettingsTestTags.dialog(tag))
        tap(SettingsTestTags.option(tag, option))
        compose.waitUntil(WAIT_MS) { !compose.exists(SettingsTestTags.dialog(tag)) }
        assertEquals(tag, expected, read(h.store.value))
    }

    fun assertInOrder(tags: List<String>) {
        for (tag in tags) assertTrue(tag, compose.exists(tag))
        val tops = tags.map { compose.topOf(it) }
        assertEquals(tags.toString(), tops.sorted(), tops)
    }

    fun assertOnlyChanged(before: DeviceSettings, expected: DeviceSettings) {
        assertEquals(expected, h.store.value)
        assertTrue(before != expected)
    }

    companion object {
        const val WAIT_MS = 5_000L
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2000dp-mdpi")
class AppearanceSettingsTest : PreferenceScreenHarness() {
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsAppearance))

    private fun open() {
        compose.showWith(navigator) { AppearanceSettingsScreen(viewModel = AppearanceSettingsViewModel(h.deps)) }
        await(TestTags.Settings.APPEARANCE_SCREEN)
    }

    @Test
    fun theThemeSectionComesFirstWithDynamicColorWhenTheDeviceHasItThenTheV031Rows() {
        open()
        assertTrue(compose.showsText(text(R.string.prefs_appearance)))
        assertTrue(compose.showsText(text(R.string.prefs_theme)))
        assertInOrder(
            listOf(
                TestTags.Settings.THEME,
                TestTags.Settings.DYNAMIC_COLOR,
                listItem(TestTags.Settings.THEME_PRESET, ThemePreset.MONOCHROME.ordinal),
                TestTags.Settings.PURE_BLACK,
                TestTags.Settings.FULL_SCREEN,
                TestTags.Settings.HIDE_BARS_ON_SCROLL,
                TestTags.Settings.HAPTICS,
                TestTags.Settings.TIME_FORMAT,
                TestTags.Settings.DATE_FORMAT,
                TestTags.Settings.RELATIVE_DATES,
                TestTags.Settings.START_SCREEN,
            ),
        )
    }

    @Test
    fun dynamicColorIsHiddenWhereTheDeviceCannotUseIt() {
        h.dynamicColor = false
        h.store.set(defaults.copy(dynamicColor = true))
        open()
        assertFalse(compose.exists(TestTags.Settings.DYNAMIC_COLOR))
        assertTrue(compose.exists(TestTags.Settings.PURE_BLACK))
        compose.onNodeWithTag(listItem(TestTags.Settings.THEME_PRESET, ThemePreset.DEFAULT.ordinal)).assert(selected())
    }

    @Test
    fun theTitlesAndSummariesAreTheV031Texts() {
        open()
        val expected = mapOf(
            TestTags.Settings.FULL_SCREEN to listOf(R.string.prefs_full_screen, R.string.prefs_full_screen_summary),
            TestTags.Settings.HIDE_BARS_ON_SCROLL to listOf(R.string.prefs_hide_bars_on_scroll, R.string.prefs_hide_bars_on_scroll_summary),
            TestTags.Settings.THEME to listOf(R.string.prefs_theme_light, R.string.theme_mode_system, R.string.prefs_theme_dark),
            TestTags.Settings.HAPTICS to listOf(R.string.prefs_haptics, R.string.prefs_haptics_summary),
            TestTags.Settings.DYNAMIC_COLOR to listOf(R.string.theme_preset_dynamic),
            TestTags.Settings.PURE_BLACK to listOf(R.string.prefs_pure_black, R.string.prefs_pure_black_summary),
            TestTags.Settings.TIME_FORMAT to listOf(R.string.prefs_time_format, R.string.prefs_time24),
            TestTags.Settings.DATE_FORMAT to listOf(R.string.prefs_date_format, R.string.prefs_date_system),
            TestTags.Settings.RELATIVE_DATES to listOf(R.string.prefs_relative_dates, R.string.prefs_relative_dates_summary),
            TestTags.Settings.START_SCREEN to listOf(R.string.prefs_start_screen, R.string.tabs_gallery),
        )
        for ((tag, ids) in expected) {
            val node = compose.onNodeWithTag(tag).performScrollTo()
            for (id in ids) node.assert(contains(text(id)))
        }
    }

    @Test
    fun everySwitchWritesItsOwnDeviceSetting() {
        open()
        toggles(TestTags.Settings.FULL_SCREEN) { it.fullScreen }
        toggles(TestTags.Settings.HIDE_BARS_ON_SCROLL) { it.hideBarsOnScroll }
        toggles(TestTags.Settings.HAPTICS) { it.hapticFeedback }
        toggles(TestTags.Settings.PURE_BLACK) { it.pureBlack }
        toggles(TestTags.Settings.RELATIVE_DATES) { it.relativeDates }
        assertEquals(defaults, h.store.value)
    }

    @Test
    fun aSwitchChangesOnlyItsField() {
        open()
        click(TestTags.Settings.PURE_BLACK)
        assertOnlyChanged(defaults, defaults.copy(pureBlack = true))
        assertTrue(compose.exists(SettingsTestTags.switch(TestTags.Settings.PURE_BLACK)))
    }

    @Test
    fun themeOffersLightSystemAndDarkInARowAndWritesTheChoice() {
        open()
        assertFalse(compose.exists(SettingsTestTags.dialog(TestTags.Settings.THEME)))
        val light = SettingsTestTags.option(TestTags.Settings.THEME, "light")
        val system = SettingsTestTags.option(TestTags.Settings.THEME, "system")
        val dark = SettingsTestTags.option(TestTags.Settings.THEME, "dark")
        compose.onNodeWithTag(system).assert(labeled(text(R.string.theme_mode_system))).assert(selected())
        compose.onNodeWithTag(light).assert(labeled(text(R.string.prefs_theme_light)))
        compose.onNodeWithTag(dark).assert(labeled(text(R.string.prefs_theme_dark)))
        assertInOrder(listOf(light, system, dark))
        assertEquals(compose.topOf(light), compose.topOf(dark))
        tap(dark)
        assertOnlyChanged(defaults, defaults.copy(appTheme = AppTheme.DARK))
        compose.onNodeWithTag(dark).assert(selected())
        compose.onNodeWithTag(TestTags.Settings.THEME).assert(contains(text(R.string.prefs_theme_dark)))
        tap(light)
        assertEquals(AppTheme.LIGHT, h.store.value.appTheme)
        tap(system)
        assertEquals(AppTheme.SYSTEM, h.store.value.appTheme)
        assertEquals(defaults, h.store.value)
    }

    @Test
    fun everyColorThemeHasALabeledTileAndTheStoredOneIsSelected() {
        open()
        for (preset in ThemePreset.entries) {
            compose.onNodeWithTag(listItem(TestTags.Settings.THEME_PRESET, preset.ordinal)).performScrollTo().assert(labeled(text(ThemePresets.label(preset))))
        }
        compose.onNodeWithTag(listItem(TestTags.Settings.THEME_PRESET, ThemePreset.DEFAULT.ordinal)).assert(selected())
        compose.onNodeWithTag(TestTags.Settings.DYNAMIC_COLOR).assert(!selected())
    }

    @Test
    fun aColorThemeTileWritesThePresetAndTurnsDynamicColorOff() {
        h.store.set(defaults.copy(dynamicColor = true))
        open()
        compose.onNodeWithTag(TestTags.Settings.DYNAMIC_COLOR).assert(selected())
        click(listItem(TestTags.Settings.THEME_PRESET, ThemePreset.NORD.ordinal))
        assertEquals(defaults.copy(themePreset = ThemePreset.NORD), h.store.value)
        compose.onNodeWithTag(listItem(TestTags.Settings.THEME_PRESET, ThemePreset.NORD.ordinal)).assert(selected())
        compose.onNodeWithTag(TestTags.Settings.DYNAMIC_COLOR).assert(!selected())
    }

    @Test
    fun theDynamicTileTurnsDynamicColorOnAndKeepsThePreset() {
        h.store.set(defaults.copy(themePreset = ThemePreset.TAKO))
        open()
        click(TestTags.Settings.DYNAMIC_COLOR)
        assertEquals(defaults.copy(themePreset = ThemePreset.TAKO, dynamicColor = true), h.store.value)
        compose.onNodeWithTag(TestTags.Settings.DYNAMIC_COLOR).assert(selected())
        compose.onNodeWithTag(listItem(TestTags.Settings.THEME_PRESET, ThemePreset.TAKO.ordinal)).assert(!selected())
    }

    @Test
    fun timeDateAndStartScreenChoicesWriteTheirFields() {
        open()
        chooses(TestTags.Settings.TIME_FORMAT, "12h", TimeFormat.HOURS_12) { it.timeFormat }
        chooses(TestTags.Settings.TIME_FORMAT, "24h", TimeFormat.HOURS_24) { it.timeFormat }
        chooses(TestTags.Settings.DATE_FORMAT, "iso", DateFormat.ISO) { it.dateFormat }
        chooses(TestTags.Settings.DATE_FORMAT, "dmy", DateFormat.DMY) { it.dateFormat }
        chooses(TestTags.Settings.DATE_FORMAT, "mdy", DateFormat.MDY) { it.dateFormat }
        chooses(TestTags.Settings.DATE_FORMAT, "system", DateFormat.SYSTEM) { it.dateFormat }
        chooses(TestTags.Settings.START_SCREEN, "calendar", StartScreen.CALENDAR) { it.startScreen }
        assertOnlyChanged(defaults, defaults.copy(startScreen = StartScreen.CALENDAR))
    }

    @Test
    fun theChoiceDialogsLabelTheirOptionsWithTheV031Texts() {
        open()
        click(TestTags.Settings.DATE_FORMAT)
        val options = mapOf("system" to R.string.prefs_date_system, "iso" to R.string.prefs_date_iso, "dmy" to R.string.prefs_date_dmy, "mdy" to R.string.prefs_date_mdy)
        for ((value, id) in options) compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.DATE_FORMAT, value)).assert(labeled(text(id)))
        tap(SettingsTestTags.option(TestTags.Settings.DATE_FORMAT, "iso"))
        click(TestTags.Settings.START_SCREEN)
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.START_SCREEN, "gallery")).assert(labeled(text(R.string.tabs_gallery)))
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.START_SCREEN, "calendar")).assert(labeled(text(R.string.tabs_calendar)))
        tap(SettingsTestTags.option(TestTags.Settings.START_SCREEN, "gallery"))
        click(TestTags.Settings.TIME_FORMAT)
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.TIME_FORMAT, "24h")).assert(labeled(text(R.string.prefs_time24)))
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.TIME_FORMAT, "12h")).assert(labeled(text(R.string.prefs_time12)))
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        open()
        tap(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }

    @Test
    fun aChangeMadeElsewhereShowsUpOnTheOpenScreen() {
        open()
        h.store.set(defaults.copy(pureBlack = true, appTheme = AppTheme.DARK))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Settings.PURE_BLACK).performScrollTo().assert(checked())
        compose.onNodeWithTag(TestTags.Settings.THEME).performScrollTo().assert(contains(text(R.string.prefs_theme_dark)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2400dp-mdpi")
class GallerySettingsTest : PreferenceScreenHarness() {
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsGallery))

    private fun badge(key: String): String = TestTags.Settings.BADGE + "." + key

    private fun open() {
        compose.showWith(navigator) { GallerySettingsScreen(viewModel = GallerySettingsViewModel(h.deps)) }
        await(TestTags.Settings.GALLERY_SCREEN)
    }

    @Test
    fun theRowsAndHeadersFollowTheV031Order() {
        open()
        assertInOrder(
            listOf(
                TestTags.Settings.DISPLAY_MODE,
                TestTags.Settings.COLUMNS_PORTRAIT,
                TestTags.Settings.COLUMNS_LANDSCAPE,
                TestTags.Settings.GROUP_BY_DAY,
                badge("video"),
                badge("photoCount"),
                badge("note"),
                badge("pending"),
                badge("keptOffline"),
                badge("favorite"),
                TestTags.Settings.SHOW_TAG_TABS,
                TestTags.Settings.SHOW_COUNTS,
                TestTags.Settings.ON_THIS_DAY,
                TestTags.Settings.CONFIRM_TRASH,
                TestTags.Settings.HIDE_SHARED,
            ),
        )
        assertTrue(compose.showsText(text(R.string.prefs_badges)))
        assertTrue(compose.showsText(text(R.string.prefs_tabs)))
        assertTrue(compose.topOf(badge("favorite")) < compose.topOf(TestTags.Settings.SHOW_TAG_TABS))
    }

    @Test
    fun theBadgeSwitchesAreLabeledWithTheV031Texts() {
        open()
        val labels = mapOf(
            "video" to R.string.prefs_badge_video,
            "photoCount" to R.string.prefs_badge_photo_count,
            "note" to R.string.prefs_badge_note,
            "pending" to R.string.prefs_badge_pending,
            "keptOffline" to R.string.prefs_badge_kept_offline,
            "favorite" to R.string.prefs_badge_favorite,
        )
        for ((key, id) in labels) compose.onNodeWithTag(badge(key)).performScrollTo().assert(contains(text(id)))
        compose.onNodeWithTag(TestTags.Settings.DISPLAY_MODE).assert(contains(text(R.string.prefs_mode_comfortable)))
        compose.onNodeWithTag(TestTags.Settings.COLUMNS_PORTRAIT).assert(contains(text(R.string.prefs_columns_auto)))
    }

    @Test
    fun displayModeOffersTheFourModes() {
        open()
        click(TestTags.Settings.DISPLAY_MODE)
        val modes = mapOf(
            "comfortableGrid" to R.string.prefs_mode_comfortable,
            "compactGrid" to R.string.prefs_mode_compact,
            "photoGrid" to R.string.prefs_mode_photo,
            "list" to R.string.prefs_mode_list,
        )
        for ((value, id) in modes) compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.DISPLAY_MODE, value)).assert(labeled(text(id)))
        tap(SettingsTestTags.option(TestTags.Settings.DISPLAY_MODE, "list"))
        assertOnlyChanged(defaults, defaults.copy(galleryDisplayMode = GalleryDisplayMode.LIST))
        chooses(TestTags.Settings.DISPLAY_MODE, "photoGrid", GalleryDisplayMode.PHOTO_GRID) { it.galleryDisplayMode }
        chooses(TestTags.Settings.DISPLAY_MODE, "compactGrid", GalleryDisplayMode.COMPACT_GRID) { it.galleryDisplayMode }
    }

    @Test
    fun columnsOfferAutoAndOneToTenForEachOrientation() {
        open()
        click(TestTags.Settings.COLUMNS_PORTRAIT)
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.COLUMNS_PORTRAIT, 0)).assert(labeled(text(R.string.prefs_columns_auto)))
        for (columns in 1..10) {
            compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.COLUMNS_PORTRAIT, columns)).performScrollTo().assert(labeled(columns.toString()))
        }
        assertFalse(compose.exists(SettingsTestTags.option(TestTags.Settings.COLUMNS_PORTRAIT, 11)))
        tap(SettingsTestTags.option(TestTags.Settings.COLUMNS_PORTRAIT, 3))
        assertOnlyChanged(defaults, defaults.copy(galleryColumnsPortrait = 3))
        click(TestTags.Settings.COLUMNS_LANDSCAPE)
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.COLUMNS_LANDSCAPE, 10)).performScrollTo()
        tap(SettingsTestTags.option(TestTags.Settings.COLUMNS_LANDSCAPE, 10))
        assertEquals(defaults.copy(galleryColumnsPortrait = 3, galleryColumnsLandscape = 10), h.store.value)
        chooses(TestTags.Settings.COLUMNS_PORTRAIT, 0, 0) { it.galleryColumnsPortrait }
    }

    @Test
    fun groupByDayWritesOnlyGalleryGroupByDayAsInV031() {
        open()
        click(TestTags.Settings.GROUP_BY_DAY)
        assertOnlyChanged(defaults, defaults.copy(galleryGroupByDay = false))
        assertEquals(GalleryGroup.DAY, h.store.value.galleryGroupBy)
    }

    @Test
    fun everyBadgeSwitchWritesItsOwnBadge() {
        open()
        toggles(badge("video")) { it.galleryBadges.video }
        toggles(badge("photoCount")) { it.galleryBadges.photoCount }
        toggles(badge("note")) { it.galleryBadges.note }
        toggles(badge("pending")) { it.galleryBadges.pending }
        toggles(badge("keptOffline")) { it.galleryBadges.keptOffline }
        toggles(badge("favorite")) { it.galleryBadges.favorite }
        click(badge("keptOffline"))
        assertOnlyChanged(defaults, defaults.copy(galleryBadges = defaults.galleryBadges.copy(keptOffline = true)))
    }

    @Test
    fun theTabAndGallerySwitchesWriteTheirFields() {
        open()
        toggles(TestTags.Settings.SHOW_TAG_TABS) { it.galleryShowTagTabs }
        toggles(TestTags.Settings.SHOW_COUNTS) { it.galleryShowCounts }
        toggles(TestTags.Settings.ON_THIS_DAY) { it.onThisDay }
        toggles(TestTags.Settings.CONFIRM_TRASH) { it.confirmTrash }
        toggles(TestTags.Settings.HIDE_SHARED) { it.hideShared }
        click(TestTags.Settings.HIDE_SHARED)
        assertOnlyChanged(defaults, defaults.copy(hideShared = true))
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        open()
        tap(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }
}
