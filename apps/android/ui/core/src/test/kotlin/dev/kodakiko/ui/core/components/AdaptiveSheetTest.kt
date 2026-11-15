package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.bounds
import dev.kodakiko.ui.core.pressBack
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class AdaptiveSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(true)

    private var dismissals = 0

    private fun sheet(enableImplicitDismiss: Boolean = true) {
        compose.setContent {
            KodakikoTheme {
                Text(string(R.string.gallery_title), modifier = Modifier.fillMaxSize().testTag(BEHIND))
                if (open) {
                    AdaptiveSheet(
                        onDismissRequest = {
                            dismissals++
                            open = false
                        },
                        modifier = Modifier.testTag(TestTags.Filter.SHEET),
                        enableImplicitDismiss = enableImplicitDismiss,
                    ) {
                        Column(Modifier.fillMaxWidth().height(SHEET_HEIGHT.dp)) {
                            Text(string(R.string.sheet_filter))
                            Text(string(R.string.sheet_sort))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheetTop(): Float = compose.onNodeWithText(string(R.string.sheet_filter), useUnmergedTree = true).bounds().top

    private fun drag(distance: Float, durationMillis: Long) {
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput {
            val start = Offset(centerX, GRAB_Y)
            swipe(start = start, end = start + Offset(0f, distance), durationMillis = durationMillis)
        }
        compose.waitForIdle()
    }

    private fun assertSprangBack(restingTop: Float) {
        assertEquals(0, dismissals)
        compose.onNodeWithTag(TestTags.Filter.SHEET).assertIsDisplayed()
        val top = sheetTop()
        assertTrue("sheet top $top did not return to $restingTop", abs(top - restingTop) <= 1f)
    }

    private fun assertClosed() {
        assertEquals(1, dismissals)
        compose.onNodeWithTag(TestTags.Filter.SHEET).assertDoesNotExist()
        compose.onNodeWithTag(BEHIND).assertIsDisplayed()
    }

    @Test
    fun theTestTagSitsOnTheSheetSurfaceAtTheBottomOfTheScreen() {
        sheet()
        val window = compose.onNode(isDialog()).assertExists().bounds()
        val surface = compose.onNodeWithTag(TestTags.Filter.SHEET)
            .assertIsDisplayed()
            .assert(hasText(string(R.string.sheet_filter)))
            .assert(hasText(string(R.string.sheet_sort)))
            .bounds()
        assertTrue("surface height ${surface.height}", abs(surface.height - SHEET_HEIGHT) <= 1f)
        assertTrue("surface bottom ${surface.bottom} vs window ${window.bottom}", abs(surface.bottom - window.bottom) <= 1f)
        assertTrue("window height ${window.height}", window.height > SHEET_HEIGHT + OUTSIDE_TAP_OFFSET)
        compose.onNodeWithText(string(R.string.sheet_filter), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theSheetFollowsTheFingerWhileDragging() {
        sheet()
        val restingTop = sheetTop()
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput {
            down(Offset(centerX, GRAB_Y))
            moveBy(Offset(0f, 30f))
            moveBy(Offset(0f, 30f))
        }
        compose.waitForIdle()
        val moved = sheetTop() - restingTop
        assertTrue("moved $moved px for a 60 px drag", moved in 40f..61f)
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput {
            advanceEventTime(HOLD_MILLIS)
            up()
        }
        compose.waitForIdle()
        assertSprangBack(restingTop)
    }

    @Test
    fun aThirtyPixelDragSpringsBack() {
        sheet()
        val restingTop = sheetTop()
        drag(distance = 30f, durationMillis = 300)
        assertSprangBack(restingTop)
    }

    @Test
    fun aSlowDragShortOfThirtyPercentSpringsBack() {
        sheet()
        val restingTop = sheetTop()
        drag(distance = 100f, durationMillis = 1_000)
        assertSprangBack(restingTop)
    }

    @Test
    fun aThreeHundredPixelDragCloses() {
        sheet()
        drag(distance = 300f, durationMillis = 600)
        assertClosed()
    }

    @Test
    fun aSlowDragPastThirtyPercentCloses() {
        sheet()
        drag(distance = 150f, durationMillis = 1_500)
        assertClosed()
    }

    @Test
    fun aQuickFlingClosesEvenWhenShort() {
        sheet()
        drag(distance = 80f, durationMillis = 32)
        assertClosed()
    }

    @Test
    fun aShortDragBelowTheFlingVelocitySpringsBack() {
        sheet()
        val restingTop = sheetTop()
        drag(distance = 80f, durationMillis = 160)
        assertSprangBack(restingTop)
    }

    @Test
    fun anUpwardDragDoesNotCloseOrLiftTheSheet() {
        sheet()
        val restingTop = sheetTop()
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput {
            val start = Offset(centerX, height - GRAB_Y)
            swipe(start = start, end = start - Offset(0f, 200f), durationMillis = 100)
        }
        compose.waitForIdle()
        assertSprangBack(restingTop)
    }

    @Test
    fun tappingOutsideCloses() {
        sheet()
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput { click(Offset(centerX, -OUTSIDE_TAP_OFFSET)) }
        compose.waitForIdle()
        assertClosed()
    }

    @Test
    fun tappingInsideKeepsTheSheetOpen() {
        sheet()
        val restingTop = sheetTop()
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput { click(center) }
        compose.waitForIdle()
        assertSprangBack(restingTop)
    }

    @Test
    fun backCloses() {
        sheet()
        compose.pressBack()
        assertClosed()
    }

    @Test
    fun withoutImplicitDismissDragsAndOutsideTapsKeepTheSheetOpen() {
        sheet(enableImplicitDismiss = false)
        val restingTop = sheetTop()
        drag(distance = 300f, durationMillis = 600)
        assertSprangBack(restingTop)
        drag(distance = 80f, durationMillis = 32)
        assertSprangBack(restingTop)
        compose.onNodeWithTag(TestTags.Filter.SHEET).performTouchInput { click(Offset(centerX, -OUTSIDE_TAP_OFFSET)) }
        compose.waitForIdle()
        assertSprangBack(restingTop)
    }

    private companion object {
        const val BEHIND = "test.behind"
        const val SHEET_HEIGHT = 400f
        const val GRAB_Y = 20f
        const val OUTSIDE_TAP_OFFSET = 100f
        const val HOLD_MILLIS = 500L
    }
}
