package dev.kodakiko.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.app.navigation.AppNavigation
import dev.kodakiko.app.navigation.AppNavigator
import dev.kodakiko.app.navigation.HomeSettings
import dev.kodakiko.app.system.SystemUi
import dev.kodakiko.app.system.SystemUiSettings
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.theme.KodakikoTheme
import dev.kodakiko.ui.core.util.systemInsetsPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class FullScreenSystemUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var settings by mutableStateOf(SystemUiSettings(fullScreen = true, secureScreen = false))

    private val navigator = AppNavigator(
        accountState = Observable<AccountState>(
            AccountState.Unlocked(AccountKeys(ByteArray(KEY_BYTES), ByteArray(KEY_BYTES), ByteArray(KEY_BYTES)), "user-1", "alice"),
        ),
        returnTo = Observable(null),
        entryExists = { true },
        startScreen = { StartScreen.GALLERY },
        clock = { System.nanoTime() / NANOS_PER_MILLI },
    )

    private fun showPlayer() {
        compose.setContent {
            KodakikoTheme(appTheme = AppTheme.LIGHT) {
                SystemUi(settings = settings, darkTheme = false) {
                    AppNavigation(navigator = navigator, homeSettings = HomeSettings(hideBarsOnScroll = true, sharedTabVisible = true)) { route ->
                        Box(Modifier.fillMaxSize().background(PAGE).systemInsetsPadding()) {
                            Box(Modifier.fillMaxSize().testTag(if (route is Route.Play) PLAYER_CONTENT else OTHER_CONTENT))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { navigator.push(Route.Play("entry-1", "media-1")) }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag(PLAYER_CONTENT)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun insets(): WindowInsetsCompat = compose.runOnIdle { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!! }

    private fun barsVisible(): Boolean = insets().let {
        it.isVisible(WindowInsetsCompat.Type.statusBars()) || it.isVisible(WindowInsetsCompat.Type.navigationBars())
    }

    private fun screenshot(): Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()

    private fun uniform(bitmap: Bitmap, fromRow: Int, toRow: Int): Boolean {
        val expected = PAGE.toArgb()
        for (row in fromRow.coerceAtLeast(0) until toRow.coerceAtMost(bitmap.height)) {
            for (column in 0 until bitmap.width) {
                if (bitmap.getPixel(column, row) != expected) return false
            }
        }
        return true
    }

    private fun pageShare(bitmap: Bitmap, fromRow: Int, toRow: Int): Float {
        val expected = PAGE.toArgb()
        var page = 0
        var total = 0
        for (row in fromRow.coerceAtLeast(0) until toRow.coerceAtMost(bitmap.height)) {
            for (column in 0 until bitmap.width) {
                total++
                if (bitmap.getPixel(column, row) == expected) page++
            }
        }
        return if (total == 0) 0f else page.toFloat() / total
    }

    private fun save(bitmap: Bitmap, name: String) {
        val directory = compose.activity.externalCacheDir ?: compose.activity.cacheDir
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    @Test
    fun aFullScreenRouteHidesBothBarsBehindASwipeAndKeepsTheMinimumInsets() {
        showPlayer()
        compose.waitUntil(WAIT_MS) { !barsVisible() }
        val controller = compose.runOnIdle { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView) }
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, controller.systemBarsBehavior)

        val statusHeight = insets().getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
        val navigationHeight = insets().getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
        assertTrue("status bar height $statusHeight", statusHeight > 0)
        var shot = screenshot()
        compose.waitUntil(WAIT_MS) {
            shot = screenshot()
            uniform(shot, 0, statusHeight) && uniform(shot, shot.height - navigationHeight, shot.height)
        }
        save(shot, "k156_full_screen")

        val density = compose.activity.resources.displayMetrics.density
        val windowHeight = compose.runOnIdle { compose.activity.window.decorView.height }
        val content = compose.onNodeWithTag(PLAYER_CONTENT).fetchSemanticsNode().boundsInWindow
        assertTrue("top ${content.top}", content.top >= (MIN_TOP_DP * density).roundToInt() - 1)
        assertTrue("bottom gap ${windowHeight - content.bottom}", windowHeight - content.bottom >= (MIN_BOTTOM_DP * density).roundToInt() - 1)
    }

    @Test
    fun turningFullScreenOffBringsTheBarsBackAboveTheContent() {
        showPlayer()
        compose.waitUntil(WAIT_MS) { !barsVisible() }
        compose.runOnIdle { settings = settings.copy(fullScreen = false) }
        compose.waitUntil(WAIT_MS) { insets().isVisible(WindowInsetsCompat.Type.statusBars()) }
        val statusHeight = insets().getInsets(WindowInsetsCompat.Type.statusBars()).top
        var shot = screenshot()
        compose.waitUntil(WAIT_MS) {
            shot = screenshot()
            !uniform(shot, 0, statusHeight)
        }
        save(shot, "k156_bars_visible")
        assertTrue("edge to edge: the page shows behind the status bar", pageShare(shot, 0, statusHeight) > EDGE_TO_EDGE_SHARE)
        val content = compose.onNodeWithTag(PLAYER_CONTENT).fetchSemanticsNode().boundsInWindow
        assertTrue("top ${content.top} status $statusHeight", content.top >= statusHeight - 1)
    }

    private companion object {
        val PAGE = Color(0xFF2E7D32)
        const val PLAYER_CONTENT = "test.playerContent"
        const val OTHER_CONTENT = "test.otherContent"
        const val KEY_BYTES = 32
        const val NANOS_PER_MILLI = 1_000_000L
        const val WAIT_MS = 5_000L
        const val PNG_QUALITY = 100
        const val MIN_TOP_DP = 28
        const val MIN_BOTTOM_DP = 20
        const val EDGE_TO_EDGE_SHARE = 0.5f
    }
}
