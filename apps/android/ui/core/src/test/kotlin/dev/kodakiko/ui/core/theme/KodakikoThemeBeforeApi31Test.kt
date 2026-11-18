package dev.kodakiko.ui.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import dev.kodakiko.data.db.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KodakikoThemeBeforeApi31Test {
    @get:Rule
    val compose = createComposeRule()

    private fun scheme(input: ThemeInput): ColorScheme {
        var captured: ColorScheme? = null
        compose.setContent {
            KodakikoTheme(appTheme = input.appTheme, pureBlack = input.pureBlack, dynamicColor = input.dynamicColor) {
                captured = MaterialTheme.colorScheme
            }
        }
        compose.waitForIdle()
        return requireNotNull(captured)
    }

    @Test
    fun dynamicColorIsNotAvailableBeforeApi31() {
        assertFalse(isDynamicColorAvailable())
    }

    @Test
    fun dynamicColorFallsBackToTheFixedLightPalette() {
        assertEquals(V031Palette.LIGHT, V031Palette.pinnedRoles(scheme(ThemeInput(AppTheme.LIGHT, dynamicColor = true))))
    }

    @Test
    fun dynamicColorFallsBackToTheFixedDarkPalette() {
        assertEquals(V031Palette.DARK, V031Palette.pinnedRoles(scheme(ThemeInput(AppTheme.DARK, dynamicColor = true))))
    }

    @Test
    fun pureBlackStillAppliesWhenDynamicColorFallsBack() {
        assertEquals(
            V031Palette.DARK_PURE_BLACK,
            V031Palette.pinnedRoles(scheme(ThemeInput(AppTheme.DARK, pureBlack = true, dynamicColor = true))),
        )
    }
}
