package dev.kodakiko.ui.core.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.Screenshots
import dev.kodakiko.ui.core.TestIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.captureScreenScreenshot
import dev.kodakiko.ui.core.captureScreenshot
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.PreferenceScreen
import dev.kodakiko.ui.core.settings.SettingsSearchContent
import dev.kodakiko.ui.core.settings.V031SettingsIndex
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
        compose.setContent {
            KodakikoTheme(appTheme = appTheme) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun widgets(): List<Preference> = listOf(
        Preference.PreferenceGroup(
            title = string(R.string.prefs_appearance),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    value = StartScreen.GALLERY,
                    entries = mapOf(
                        StartScreen.GALLERY to string(R.string.tabs_gallery),
                        StartScreen.CALENDAR to string(R.string.tabs_calendar),
                    ),
                    title = string(R.string.prefs_start_screen),
                    icon = TestIcons.Square,
                    testTag = TestTags.Settings.START_SCREEN,
                    onValueChanged = {},
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = true,
                    title = string(R.string.prefs_full_screen),
                    subtitle = string(R.string.prefs_full_screen_summary),
                    testTag = TestTags.Settings.FULL_SCREEN,
                    onValueChanged = {},
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = false,
                    title = string(R.string.prefs_pure_black),
                    testTag = TestTags.Settings.PURE_BLACK,
                    onValueChanged = {},
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = false,
                    title = string(R.string.settings_biometric),
                    testTag = TestTags.Settings.BIOMETRIC,
                    enabled = false,
                    onValueChanged = {},
                ),
            ),
        ),
        Preference.PreferenceGroup(
            title = string(R.string.prefs_gallery),
            preferenceItems = listOf(
                Preference.PreferenceItem.MultiSelectListPreference(
                    values = setOf("video", "note"),
                    entries = mapOf(
                        "video" to string(R.string.prefs_badge_video),
                        "photoCount" to string(R.string.prefs_badge_photo_count),
                        "note" to string(R.string.prefs_badge_note),
                    ),
                    title = string(R.string.prefs_badges),
                    testTag = TestTags.Settings.BADGE,
                    onValuesChanged = {},
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = 4,
                    valueRange = 0..10,
                    title = string(R.string.prefs_columns_portrait),
                    testTag = TestTags.Settings.COLUMNS_PORTRAIT,
                    onValueChanged = {},
                ),
                Preference.PreferenceItem.InfoPreference(title = string(R.string.prefs_hide_bars_on_scroll_summary)),
            ),
        ),
        Preference.PreferenceItem.TextPreference(
            title = string(R.string.prefs_appearance),
            subtitle = string(R.string.prefs_appearance_summary),
            icon = TestIcons.Square,
            testTag = TestTags.Settings.ROW_APPEARANCE,
            onClick = {},
        ),
        Preference.PreferenceItem.TextPreference(
            title = string(R.string.settings_sign_out),
            icon = TestIcons.Square,
            testTag = TestTags.Settings.ROW_SIGN_OUT,
            danger = true,
            onClick = {},
        ),
    )

    @Test
    @Config(qualifiers = TALL_QUALIFIERS)
    fun widgetsLight() {
        show { PreferenceScreen(items = widgets()) }
        compose.onRoot().captureScreenshot("settings/widgets_light")
    }

    @Test
    @Config(qualifiers = TALL_QUALIFIERS)
    fun widgetsDark() {
        show(AppTheme.DARK) { PreferenceScreen(items = widgets()) }
        compose.onRoot().captureScreenshot("settings/widgets_dark")
    }

    @Test
    fun listDialog() {
        show { PreferenceScreen(items = widgets()) }
        compose.onNodeWithTag(TestTags.Settings.START_SCREEN).performClick()
        compose.waitForIdle()
        captureScreenScreenshot("settings/list_dialog")
    }

    @Test
    fun multiSelectDialog() {
        show { PreferenceScreen(items = widgets()) }
        compose.onNodeWithTag(TestTags.Settings.BADGE).performClick()
        compose.waitForIdle()
        captureScreenScreenshot("settings/multi_select_dialog")
    }

    @Test
    fun highlightedItem() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KodakikoTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PreferenceScreen(items = widgets(), highlightKey = string(R.string.prefs_full_screen))
                }
            }
        }
        compose.mainClock.advanceTimeBy(HIGHLIGHT_PEAK_MS)
        compose.onRoot().captureScreenshot("settings/highlighted_item")
    }

    @Test
    fun searchResults() {
        show {
            SettingsSearchContent(sections = V031SettingsIndex.sections(), onResultClick = {}, navigateUp = {})
        }
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput("screen")
        compose.waitForIdle()
        compose.onRoot().captureScreenshot("settings/search_results")
    }

    @Test
    fun searchNoResults() {
        show {
            SettingsSearchContent(sections = V031SettingsIndex.sections(), onResultClick = {}, navigateUp = {})
        }
        compose.onNodeWithTag(TestTags.Settings.SEARCH_FIELD).performTextInput("zebra")
        compose.waitForIdle()
        compose.onRoot().captureScreenshot("settings/search_no_results")
    }

    private companion object {
        const val HIGHLIGHT_PEAK_MS = 800L
        const val TALL_QUALIFIERS = "w360dp-h1280dp-xhdpi"
    }
}
