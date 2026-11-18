package dev.kodakiko.ui.core.util

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemInsetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zero = EdgeInsets(top = 0.dp, bottom = 0.dp, left = 0.dp, right = 0.dp)

    @Test
    fun theFullScreenMinimumsAre28And20Dp() {
        assertEquals(28.dp, SystemInsetsConstants.FULL_SCREEN_MIN_TOP)
        assertEquals(20.dp, SystemInsetsConstants.FULL_SCREEN_MIN_BOTTOM)
    }

    @Test
    fun keepsAGapForTheCameraCutoutAndTheGestureBarInFullScreen() {
        assertEquals(
            zero.copy(top = SystemInsetsConstants.FULL_SCREEN_MIN_TOP, bottom = SystemInsetsConstants.FULL_SCREEN_MIN_BOTTOM),
            systemInsets(zero, fullScreen = true),
        )
    }

    @Test
    fun keepsLargerRealInsetsAndChangesNothingOutsideFullScreen() {
        assertEquals(zero.copy(top = 40.dp, bottom = 30.dp), systemInsets(zero.copy(top = 40.dp, bottom = 30.dp), fullScreen = true))
        assertEquals(zero, systemInsets(zero, fullScreen = false))
        assertEquals(
            EdgeInsets(top = 10.dp, bottom = 5.dp, left = 3.dp, right = 4.dp),
            systemInsets(EdgeInsets(top = 10.dp, bottom = 5.dp, left = 3.dp, right = 4.dp), fullScreen = false),
        )
        assertEquals(
            EdgeInsets(top = 28.dp, bottom = 20.dp, left = 3.dp, right = 4.dp),
            systemInsets(EdgeInsets(top = 10.dp, bottom = 5.dp, left = 3.dp, right = 4.dp), fullScreen = true),
        )
    }

    @Test
    fun theInsetsPaddingAppliesTheMinimumsOnlyInFullScreen() {
        var fullScreen by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalFullScreen provides fullScreen) {
                Box(Modifier.fillMaxSize().systemInsetsPadding()) {
                    Box(Modifier.fillMaxSize().testTag(CONTENT))
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val padded = compose.onNodeWithTag(CONTENT).fetchSemanticsNode().boundsInRoot
        assertEquals(28f * density, padded.top - root.top, TOLERANCE_PX)
        assertEquals(20f * density, root.bottom - padded.bottom, TOLERANCE_PX)
        assertEquals(0f, padded.left - root.left, TOLERANCE_PX)
        compose.runOnIdle { fullScreen = false }
        compose.waitForIdle()
        val unpadded = compose.onNodeWithTag(CONTENT).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, unpadded.top - root.top, TOLERANCE_PX)
        assertEquals(0f, root.bottom - unpadded.bottom, TOLERANCE_PX)
    }

    @Test
    fun fullScreenIsOffUnlessProvided() {
        var seen: Boolean? = null
        compose.setContent { seen = LocalFullScreen.current }
        compose.waitForIdle()
        assertEquals(false, seen)
    }

    private companion object {
        const val CONTENT = "test.insetsContent"
        const val TOLERANCE_PX = 1f
    }
}
