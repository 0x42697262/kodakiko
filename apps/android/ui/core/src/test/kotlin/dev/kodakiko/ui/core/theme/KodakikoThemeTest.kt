package dev.kodakiko.ui.core.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

data class ThemeInput(val appTheme: AppTheme, val pureBlack: Boolean = false, val dynamicColor: Boolean = false)

@RunWith(RobolectricTestRunner::class)
class KodakikoThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun schemes(vararg inputs: ThemeInput): List<ColorScheme> {
        var input by mutableStateOf(inputs.first())
        var captured: ColorScheme? = null
        compose.setContent {
            KodakikoTheme(appTheme = input.appTheme, pureBlack = input.pureBlack, dynamicColor = input.dynamicColor) {
                captured = MaterialTheme.colorScheme
            }
        }
        return inputs.map { next ->
            compose.runOnIdle { input = next }
            compose.waitForIdle()
            requireNotNull(captured)
        }
    }

    private fun assertRoles(expected: Map<String, Long>, scheme: ColorScheme, label: String) {
        val actual = V031Palette.pinnedRoles(scheme)
        val wrong = expected.filter { (role, value) -> actual[role] != value }
            .map { (role, value) -> "$role expected ${value.toString(16)} but was ${actual[role]?.toString(16)}" }
        assertTrue("$label: $wrong", wrong.isEmpty())
    }

    @Test
    @Config(qualifiers = "notnight")
    fun systemThemeFollowsALightSystemWithTheV031LightPalette() {
        val (system) = schemes(ThemeInput(AppTheme.SYSTEM))
        assertRoles(V031Palette.LIGHT, system, "system on a light device")
    }

    @Test
    @Config(qualifiers = "night")
    fun systemThemeFollowsADarkSystemWithTheV031DarkPalette() {
        val (system) = schemes(ThemeInput(AppTheme.SYSTEM))
        assertRoles(V031Palette.DARK, system, "system on a dark device")
    }

    @Test
    @Config(qualifiers = "night")
    fun lightChoiceWinsOverADarkSystem() {
        val (light) = schemes(ThemeInput(AppTheme.LIGHT))
        assertRoles(V031Palette.LIGHT, light, "light on a dark device")
    }

    @Test
    @Config(qualifiers = "notnight")
    fun darkChoiceWinsOverALightSystem() {
        val (dark) = schemes(ThemeInput(AppTheme.DARK))
        assertRoles(V031Palette.DARK, dark, "dark on a light device")
    }

    @Test
    @Config(qualifiers = "notnight")
    fun theDeviceSettingsDefaultsGiveTheSystemThemeWithoutPureBlackOrDynamicColor() {
        val defaults = DeviceSettingsDefaults.ANDROID
        assertEquals(AppTheme.SYSTEM, defaults.appTheme)
        assertFalse(defaults.pureBlack)
        assertFalse(defaults.dynamicColor)
        val (scheme) = schemes(ThemeInput(defaults.appTheme, defaults.pureBlack, defaults.dynamicColor))
        assertRoles(V031Palette.LIGHT, scheme, "defaults on a light device")
    }

    @Test
    @Config(qualifiers = "notnight")
    fun pureBlackTurnsDarkSurfacesBlackAndLeavesTheOtherDarkRoles() {
        val (black) = schemes(ThemeInput(AppTheme.DARK, pureBlack = true))
        assertRoles(V031Palette.DARK_PURE_BLACK, black, "dark + pure black")
    }

    @Test
    @Config(qualifiers = "night")
    fun pureBlackAppliesToTheSystemThemeOnlyWhileTheSystemIsDark() {
        val (black) = schemes(ThemeInput(AppTheme.SYSTEM, pureBlack = true))
        assertRoles(V031Palette.DARK_PURE_BLACK, black, "system (dark) + pure black")
    }

    @Test
    @Config(qualifiers = "notnight")
    fun pureBlackLeavesTheLightThemeUnchanged() {
        val (light, systemLight) = schemes(
            ThemeInput(AppTheme.LIGHT, pureBlack = true),
            ThemeInput(AppTheme.SYSTEM, pureBlack = true),
        )
        assertRoles(V031Palette.LIGHT, light, "light + pure black")
        assertRoles(V031Palette.LIGHT, systemLight, "system (light) + pure black")
    }

    @Test
    @Config(qualifiers = "notnight")
    fun switchingTheSettingsRecomposesWithTheNewScheme() {
        val (light, dark, black, lightAgain) = schemes(
            ThemeInput(AppTheme.LIGHT),
            ThemeInput(AppTheme.DARK),
            ThemeInput(AppTheme.DARK, pureBlack = true),
            ThemeInput(AppTheme.SYSTEM),
        )
        assertRoles(V031Palette.LIGHT, light, "light")
        assertRoles(V031Palette.DARK, dark, "dark")
        assertRoles(V031Palette.DARK_PURE_BLACK, black, "dark + pure black")
        assertRoles(V031Palette.LIGHT, lightAgain, "system (light)")
    }

    @Test
    fun dynamicColorIsAvailableFromApi31() {
        assertTrue(isDynamicColorAvailable())
    }

    @Test
    @Config(qualifiers = "notnight")
    fun dynamicColorUsesTheSystemLightSchemeOnApi31AndLater() {
        val expected = V031Palette.allRoles(dynamicLightColorScheme(context))
        assertNotEquals(V031Palette.LIGHT.getValue("primary"), expected.getValue("primary"))
        val (dynamic) = schemes(ThemeInput(AppTheme.LIGHT, dynamicColor = true))
        assertEquals(expected, V031Palette.allRoles(dynamic))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun dynamicColorUsesTheSystemDarkSchemeOnApi31AndLater() {
        val expected = V031Palette.allRoles(dynamicDarkColorScheme(context))
        assertNotEquals(V031Palette.DARK.getValue("primary"), expected.getValue("primary"))
        val (dynamic) = schemes(ThemeInput(AppTheme.DARK, dynamicColor = true))
        assertEquals(expected, V031Palette.allRoles(dynamic))
    }

    @Test
    @Config(qualifiers = "night")
    fun pureBlackIsAppliedOnTopOfDynamicColorLikeV031() {
        val expected = V031Palette.allRoles(dynamicDarkColorScheme(context)) + V031Palette.PURE_BLACK_OVERRIDES
        val (dynamicBlack) = schemes(ThemeInput(AppTheme.SYSTEM, pureBlack = true, dynamicColor = true))
        assertEquals(expected, V031Palette.allRoles(dynamicBlack))
    }

    @Test
    @Config(qualifiers = "notnight")
    fun dynamicColorOffKeepsTheFixedPaletteOnApi31AndLater() {
        val (light, dark) = schemes(ThemeInput(AppTheme.LIGHT), ThemeInput(AppTheme.DARK))
        assertRoles(V031Palette.LIGHT, light, "light, dynamic off")
        assertRoles(V031Palette.DARK, dark, "dark, dynamic off")
    }

    @Test
    fun typographyAndShapesAreTheMaterial3BaselineLikeMihon() {
        var typography: Typography? = null
        var shapes: Shapes? = null
        compose.setContent {
            KodakikoTheme {
                typography = MaterialTheme.typography
                shapes = MaterialTheme.shapes
            }
        }
        compose.waitForIdle()
        assertEquals(Typography(), typography)
        assertEquals(Shapes(), shapes)
    }
}
