package dev.kodakiko.app.system

import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.kodakiko.app.MainActivity
import dev.kodakiko.app.RegisterComponentActivity
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.ui.core.util.LocalFullScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class SystemUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

    private fun secureFlag(): Boolean = compose.runOnIdle {
        compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private fun decorFits(window: Window): Boolean = ReflectionHelpers.getField(window, "mDecorFitsSystemWindows")

    private fun controller(): WindowInsetsControllerCompat =
        compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView) }

    @Test
    fun settingsMapToSystemUiAndFailClosedWhileLoading() {
        val defaults = DeviceSettingsDefaults.ANDROID
        assertEquals(SystemUiSettings(fullScreen = true, secureScreen = true), SystemUiSettings.of(defaults, loaded = true))
        assertEquals(
            SystemUiSettings(fullScreen = false, secureScreen = false),
            SystemUiSettings.of(defaults.copy(fullScreen = false, secureScreen = false), loaded = true),
        )
        assertEquals(
            SystemUiSettings(fullScreen = true, secureScreen = true),
            SystemUiSettings.of(defaults.copy(fullScreen = false, secureScreen = false), loaded = false),
        )
        assertEquals(
            SystemUiSettings(fullScreen = false, secureScreen = true),
            SystemUiSettings.of(defaults.copy(fullScreen = false, secureScreen = true), loaded = true),
        )
    }

    @Test
    fun theSecureFlagFollowsTheSetting() {
        var settings by mutableStateOf(SystemUiSettings(fullScreen = false, secureScreen = true))
        compose.setContent { SystemUi(settings = settings, darkTheme = false) {} }
        compose.waitForIdle()
        assertTrue(secureFlag())
        compose.runOnIdle { settings = settings.copy(secureScreen = false) }
        compose.waitForIdle()
        assertFalse(secureFlag())
        compose.runOnIdle { settings = settings.copy(secureScreen = true) }
        compose.waitForIdle()
        assertTrue(secureFlag())
    }

    @Test
    fun fullScreenUsesTransientBarsAndTellsTheContent() {
        var settings by mutableStateOf(SystemUiSettings(fullScreen = true, secureScreen = false))
        val seen = mutableListOf<Boolean>()
        compose.setContent {
            SystemUi(settings = settings, darkTheme = false) {
                seen += LocalFullScreen.current
            }
        }
        compose.waitForIdle()
        assertEquals(true, seen.last())
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, controller().systemBarsBehavior)
        assertFalse(secureFlag())
        compose.runOnIdle { settings = settings.copy(fullScreen = false) }
        compose.waitForIdle()
        assertEquals(false, seen.last())
    }

    @Test
    fun barIconsFollowTheTheme() {
        var dark by mutableStateOf(false)
        compose.setContent { SystemUi(settings = SystemUiSettings(fullScreen = false, secureScreen = false), darkTheme = dark) {} }
        compose.waitForIdle()
        assertTrue(controller().isAppearanceLightStatusBars)
        assertTrue(controller().isAppearanceLightNavigationBars)
        compose.runOnIdle { dark = true }
        compose.waitForIdle()
        assertFalse(controller().isAppearanceLightStatusBars)
        assertFalse(controller().isAppearanceLightNavigationBars)
    }

    @Test
    fun theWindowDrawsEdgeToEdge() {
        compose.setContent { SystemUi(settings = SystemUiSettings(fullScreen = false, secureScreen = false), darkTheme = false) {} }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(decorFits(compose.activity.window))
            assertFalse(compose.activity.window.isNavigationBarContrastEnforced)
        }
    }

    @Test
    fun mainActivityStartsEdgeToEdgeWithoutANavigationBarScrimAndSecure() {
        val window = Robolectric.buildActivity(MainActivity::class.java).setup().get().window
        assertFalse(decorFits(window))
        assertFalse(window.isNavigationBarContrastEnforced)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
