package dev.kodakiko.ui.core.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemePresetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everyPresetHasItsOwnLabel() {
        val labels = ThemePreset.entries.associateWith { context.getString(ThemePresets.label(it)) }
        assertEquals(
            mapOf(
                ThemePreset.DEFAULT to "Default",
                ThemePreset.CATPPUCCIN to "Catppuccin",
                ThemePreset.GREEN_APPLE to "Green Apple",
                ThemePreset.LAVENDER to "Lavender",
                ThemePreset.MIDNIGHT_DUSK to "Midnight Dusk",
                ThemePreset.NORD to "Nord",
                ThemePreset.STRAWBERRY to "Strawberry",
                ThemePreset.TAKO to "Tako",
                ThemePreset.TEAL_TURQUOISE to "Teal & Turquoise",
                ThemePreset.TIDAL_WAVE to "Tidal Wave",
                ThemePreset.TOKYO_NIGHT to "Tokyo Night",
                ThemePreset.YIN_YANG to "Yin & Yang",
                ThemePreset.YOTSUBA to "Yotsuba",
                ThemePreset.MONOCHROME to "Monochrome",
            ),
            labels,
        )
    }

    @Test
    fun everyPresetHasALightAndADarkScheme() {
        for (preset in ThemePreset.entries) {
            val light = ThemePresets.colorScheme(preset, isDark = false, isPureBlack = false)
            val dark = ThemePresets.colorScheme(preset, isDark = true, isPureBlack = false)
            assertTrue("$preset light background", light.background.luminance() > dark.background.luminance())
            assertTrue("$preset light text", light.onBackground.luminance() < dark.onBackground.luminance())
            assertNotEquals("$preset primary", light.primary, light.background)
            assertNotEquals("$preset dark primary", dark.primary, dark.background)
            assertEquals("$preset pure black does not change light", light, ThemePresets.colorScheme(preset, isDark = false, isPureBlack = true))
        }
    }

    @Test
    fun theColorThemesDifferFromEachOther() {
        val primaries = ThemePreset.entries.map { ThemePresets.colorScheme(it, isDark = true, isPureBlack = false) }.map { it.primary to it.background }
        assertEquals(ThemePreset.entries.size, primaries.toSet().size)
    }

    @Test
    fun pureBlackBlacksTheDarkBackgroundAndSurfaceOfEveryPreset() {
        for (preset in ThemePreset.entries) {
            val dark = ThemePresets.colorScheme(preset, isDark = true, isPureBlack = false)
            val black = ThemePresets.colorScheme(preset, isDark = true, isPureBlack = true)
            assertEquals("$preset background", Color.Black, black.background)
            assertEquals("$preset surface", Color.Black, black.surface)
            assertEquals("$preset onBackground", Color.White, black.onBackground)
            assertEquals("$preset primary", dark.primary, black.primary)
        }
    }

    @Test
    fun theThemeUsesTheChosenPresetAndDefaultIsTheStartingPreset() {
        assertEquals(ThemePreset.DEFAULT, DeviceSettingsDefaults.ANDROID.themePreset)
        var captured: ColorScheme? = null
        compose.setContent {
            KodakikoTheme(appTheme = AppTheme.DARK, pureBlack = false, dynamicColor = false, preset = ThemePreset.NORD) {
                captured = MaterialTheme.colorScheme
            }
        }
        compose.waitForIdle()
        assertEquals(ThemePresets.colorScheme(ThemePreset.NORD, isDark = true, isPureBlack = false), captured)
    }
}
