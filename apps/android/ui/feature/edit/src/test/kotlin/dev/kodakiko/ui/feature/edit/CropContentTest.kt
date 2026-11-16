package dev.kodakiko.ui.feature.edit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.kodakiko.service.entries.CROP_MIN_FRACTION
import dev.kodakiko.service.entries.CropCorner
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.FULL_CROP
import dev.kodakiko.ui.core.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class CropContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<CropAction>()

    private fun show(state: CropState) {
        compose.show { CropContent(state = state, onAction = { actions += it }) }
    }

    private fun showStateful(initial: CropState): MutableState<CropState> {
        val holder = mutableStateOf(initial)
        compose.show {
            CropContent(
                state = holder.value,
                onAction = { action ->
                    actions += action
                    if (action is CropAction.RectChanged) holder.value = holder.value.copy(rect = action.rect)
                },
            )
        }
        return holder
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun bounds(tag: String): Rect = node(tag).fetchSemanticsNode().boundsInRoot

    private fun handle(corner: CropCorner): String = when (corner) {
        CropCorner.TOP_LEFT -> "crop.topLeft"
        CropCorner.TOP_RIGHT -> "crop.topRight"
        CropCorner.BOTTOM_LEFT -> "crop.bottomLeft"
        CropCorner.BOTTOM_RIGHT -> "crop.bottomRight"
    }

    private companion object {
        const val STEP_PX = 6f
    }

    private fun drag(tag: String, deltaX: Float, deltaY: Float) {
        val target = node(tag)
        target.performTouchInput { down(center) }
        compose.waitForIdle()
        val steps = maxOf(1, ceil(maxOf(abs(deltaX), abs(deltaY)) / STEP_PX).toInt())
        target.performTouchInput {
            for (step in 1..steps) moveBy(Offset(deltaX / steps, deltaY / steps))
            up()
        }
        compose.waitForIdle()
    }

    private fun near(expected: Double, actual: Double, tolerance: Double, label: String) {
        assertTrue("$label: expected $expected but was $actual", abs(expected - actual) <= tolerance)
    }

    @Test
    fun theScreenShowsTheImageTheAreaFourHandlesApplyAndCancel() {
        show(EditStates.crop())
        assertTrue(compose.exists(TestTags.Crop.SHEET))
        assertTrue(compose.exists(TestTags.Crop.AREA))
        for (corner in CropCorner.entries) assertTrue(handle(corner), compose.exists(handle(corner)))
        node(TestTags.Crop.APPLY).assertTextContains("Apply").assertIsEnabled()
        node(TestTags.Crop.CANCEL).assertTextContains("Cancel").assertIsEnabled()
        assertFalse(compose.exists(TestTags.Crop.FAILED))
        node(TestTags.Crop.CANCEL).performClick()
        node(TestTags.Crop.APPLY).performClick()
        compose.waitForIdle()
        assertEquals(listOf(CropAction.Cancel, CropAction.Apply), actions)
    }

    @Test
    fun theFrameKeepsThePhotosShapeAndTheHandlesSitOnTheCorners() {
        show(EditStates.crop())
        val area = bounds(TestTags.Crop.AREA)
        near(4.0 / 3.0, (area.width / area.height).toDouble(), 0.02, "aspect")
        val bottomRight = bounds(handle(CropCorner.BOTTOM_RIGHT)).center
        near(area.right.toDouble(), bottomRight.x.toDouble(), 1.5, "bottomRight.x")
        near(area.bottom.toDouble(), bottomRight.y.toDouble(), 1.5, "bottomRight.y")
        val topLeft = bounds(handle(CropCorner.TOP_LEFT)).center
        near(area.left.toDouble(), topLeft.x.toDouble(), 1.5, "topLeft.x")
        near(area.top.toDouble(), topLeft.y.toDouble(), 1.5, "topLeft.y")
    }

    @Test
    fun theAreaAndHandlesFollowTheRectangle() {
        show(EditStates.crop(rect = CropRect(0.25, 0.5, 0.5, 0.25)))
        val area = bounds(TestTags.Crop.AREA)
        val topRight = bounds(handle(CropCorner.TOP_RIGHT)).center
        near(area.right.toDouble(), topRight.x.toDouble(), 1.5, "topRight.x")
        near(area.top.toDouble(), topRight.y.toDouble(), 1.5, "topRight.y")
        near(1.0, (area.width / area.height).toDouble() * 3.0 / 4.0 / 2.0, 0.03, "half width, quarter height")
    }

    @Test
    fun draggingTheBottomRightHandleShrinksTheRectangleFromThatCorner() {
        val screen = showStateful(EditStates.crop())
        val frame = bounds(TestTags.Crop.AREA)
        drag(handle(CropCorner.BOTTOM_RIGHT), -frame.width * 0.3f, -frame.height * 0.2f)
        val rect = screen.value.rect
        assertEquals(0.0, rect.x, 0.0)
        assertEquals(0.0, rect.y, 0.0)
        near(0.7, rect.width, 0.04, "width")
        near(0.8, rect.height, 0.04, "height")
        assertTrue(actions.all { it is CropAction.RectChanged })
    }

    @Test
    fun aCornerStopsAtTheMinimumSize() {
        val screen = showStateful(EditStates.crop())
        val frame = bounds(TestTags.Crop.AREA)
        drag(handle(CropCorner.TOP_LEFT), frame.width * 0.97f, frame.height * 0.97f)
        val rect = screen.value.rect
        assertEquals(CROP_MIN_FRACTION, rect.width, 1e-9)
        assertEquals(CROP_MIN_FRACTION, rect.height, 1e-9)
        assertEquals(1.0, rect.x + rect.width, 1e-9)
        assertEquals(1.0, rect.y + rect.height, 1e-9)
    }

    @Test
    fun aCornerCannotLeaveTheImage() {
        val screen = showStateful(EditStates.crop(rect = CropRect(0.2, 0.2, 0.5, 0.5)))
        val frame = bounds(TestTags.Crop.AREA)
        drag(handle(CropCorner.TOP_LEFT), -frame.width * 0.5f, -frame.height * 0.5f)
        val rect = screen.value.rect
        assertEquals(0.0, rect.x, 0.0)
        assertEquals(0.0, rect.y, 0.0)
        assertEquals(0.7, rect.width, 1e-9)
        assertEquals(0.7, rect.height, 1e-9)
    }

    @Test
    fun draggingTheMiddleMovesTheRectangleButNotOutOfTheImage() {
        val screen = showStateful(EditStates.crop(rect = CropRect(0.2, 0.2, 0.5, 0.5)))
        val area = bounds(TestTags.Crop.AREA)
        drag(TestTags.Crop.AREA, area.width * 2f, -area.height * 2f)
        val rect = screen.value.rect
        assertEquals(0.5, rect.x, 1e-9)
        assertEquals(0.0, rect.y, 1e-9)
        assertEquals(0.5, rect.width, 1e-9)
        assertEquals(0.5, rect.height, 1e-9)
    }

    @Test
    fun draggingTheMiddleAShortWayMovesItByThatMuch() {
        val screen = showStateful(EditStates.crop(rect = CropRect(0.2, 0.2, 0.5, 0.5)))
        val area = bounds(TestTags.Crop.AREA)
        val frameWidth = area.width / 0.5f
        drag(TestTags.Crop.AREA, frameWidth * 0.2f, 0f)
        val rect = screen.value.rect
        near(0.4, rect.x, 0.04, "x")
        assertEquals(0.2, rect.y, 1e-9)
        assertEquals(0.5, rect.width, 1e-9)
    }

    @Test
    fun whileApplyingBothButtonsAreDisabled() {
        show(EditStates.crop(busy = true))
        node(TestTags.Crop.APPLY).assertIsNotEnabled()
        node(TestTags.Crop.CANCEL).assertIsNotEnabled()
    }

    @Test
    fun aFailedCropSaysSo() {
        show(EditStates.crop(failed = true))
        node(TestTags.Crop.FAILED).assertTextContains("Couldn't crop this photo.")
        node(TestTags.Crop.APPLY).assertIsEnabled()
    }

    @Test
    fun whileTheOriginalLoadsThereIsNothingToDragOrApply() {
        show(EditStates.crop(loaded = false))
        assertTrue(compose.exists(TestTags.Crop.SHEET))
        assertFalse(compose.exists(TestTags.Crop.AREA))
        assertFalse(compose.exists(handle(CropCorner.BOTTOM_RIGHT)))
        node(TestTags.Crop.APPLY).assertIsNotEnabled()
        node(TestTags.Crop.CANCEL).assertIsEnabled()
    }

    @Test
    fun theStartingRectangleIsTheWholeImage() {
        val screen = showStateful(EditStates.crop())
        assertEquals(FULL_CROP, screen.value.rect)
    }
}
