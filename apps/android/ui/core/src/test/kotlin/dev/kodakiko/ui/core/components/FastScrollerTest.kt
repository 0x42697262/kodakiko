package dev.kodakiko.ui.core.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.bounds
import dev.kodakiko.ui.core.isNotShown
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class FastScrollerTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var listState: LazyListState

    private lateinit var gridState: LazyGridState

    private lateinit var scope: CoroutineScope

    private fun list(count: Int) {
        compose.setContent {
            KodakikoTheme {
                scope = rememberCoroutineScope()
                listState = rememberLazyListState()
                VerticalFastScroller(listState = listState, modifier = Modifier.testTag(SCROLLER)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(count) { index -> Text("row $index", modifier = Modifier.height(ROW_HEIGHT.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun grid(count: Int) {
        compose.setContent {
            KodakikoTheme {
                scope = rememberCoroutineScope()
                gridState = rememberLazyGridState()
                VerticalGridFastScroller(state = gridState, modifier = Modifier.testTag(SCROLLER)) {
                    LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS), state = gridState, modifier = Modifier.fillMaxSize()) {
                        items(count) { index -> Text("cell $index", modifier = Modifier.height(ROW_HEIGHT.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun jump(scroll: suspend () -> Unit) {
        compose.runOnIdle { runBlocking { scroll() } }
        compose.waitForIdle()
    }

    private fun reveal(state: ScrollableState, distance: Float = REVEAL_SCROLL) {
        compose.runOnIdle { scope.launch { state.animateScrollBy(distance) } }
        compose.mainClock.advanceTimeBy(REVEAL_MILLIS)
    }

    private fun dragThumbTo(targetY: Float) {
        val thumb = compose.onNodeWithTag(TestTags.FastScroller.THUMB).bounds()
        val step = (targetY - thumb.center.y) / DRAG_STEPS
        compose.onNodeWithTag(TestTags.FastScroller.THUMB).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(FRAME_MILLIS)
        repeat(DRAG_STEPS) {
            compose.onNodeWithTag(TestTags.FastScroller.THUMB).performTouchInput { moveBy(Offset(0f, step)) }
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
        }
        compose.onNodeWithTag(TestTags.FastScroller.THUMB).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
    }

    private fun lastVisibleListIndex(): Int = listState.layoutInfo.visibleItemsInfo.last().index

    @Test
    fun theThumbAppearsAfterScrollingAndTracksThePosition() {
        list(ITEM_COUNT)
        reveal(listState)
        val scroller = compose.onNodeWithTag(SCROLLER).bounds()
        val nearTop = compose.onNodeWithTag(TestTags.FastScroller.THUMB).bounds()
        assertTrue("thumb at ${nearTop.top} is not near the top of $scroller", nearTop.top - scroller.top < scroller.height * 0.1f)
        assertTrue("thumb sits at the end edge", scroller.right - nearTop.right < 24f)
        jump { listState.scrollToItem(ITEM_COUNT / 2) }
        reveal(listState)
        val middle = compose.onNodeWithTag(TestTags.FastScroller.THUMB).bounds()
        val fraction = (middle.center.y - scroller.top) / scroller.height
        assertTrue("thumb fraction $fraction for the middle item", fraction in 0.35f..0.65f)
    }

    @Test
    fun draggingTheThumbToTheBottomReachesTheLastItem() {
        list(ITEM_COUNT)
        reveal(listState)
        val scroller = compose.onNodeWithTag(SCROLLER).bounds()
        dragThumbTo(scroller.bottom)
        assertEquals(ITEM_COUNT - 1, lastVisibleListIndex())
    }

    @Test
    fun draggingTheThumbBackToTheTopReachesTheFirstItem() {
        list(ITEM_COUNT)
        jump { listState.scrollToItem(ITEM_COUNT - 20) }
        reveal(listState, -REVEAL_SCROLL)
        val scroller = compose.onNodeWithTag(SCROLLER).bounds()
        dragThumbTo(scroller.top)
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun draggingTheThumbToTheMiddleShowsTheMiddleOfTheList() {
        list(ITEM_COUNT)
        reveal(listState)
        val scroller = compose.onNodeWithTag(SCROLLER).bounds()
        dragThumbTo(scroller.center.y)
        val first = listState.firstVisibleItemIndex
        assertTrue("first visible $first after dragging to the middle", first in (ITEM_COUNT * 35 / 100)..(ITEM_COUNT * 65 / 100))
    }

    @Test
    fun aListThatFitsHasNoThumb() {
        list(SHORT_COUNT)
        reveal(listState)
        assertTrue(compose.onNodeWithTag(TestTags.FastScroller.THUMB).isNotShown())
    }

    @Test
    fun theGridScrollerReachesTheLastRow() {
        grid(ITEM_COUNT * GRID_COLUMNS)
        reveal(gridState)
        val scroller = compose.onNodeWithTag(SCROLLER).bounds()
        dragThumbTo(scroller.bottom)
        assertEquals(ITEM_COUNT * GRID_COLUMNS - 1, gridState.layoutInfo.visibleItemsInfo.last().index)
        dragThumbTo(scroller.top)
        assertEquals(0, gridState.firstVisibleItemIndex)
    }

    private companion object {
        const val SCROLLER = "test.scroller"
        const val ITEM_COUNT = 200
        const val SHORT_COUNT = 5
        const val GRID_COLUMNS = 3
        const val ROW_HEIGHT = 48f
        const val REVEAL_MILLIS = 800L
        const val REVEAL_SCROLL = 150f
        const val SETTLE_MILLIS = 500L
        const val DRAG_STEPS = 20
        const val FRAME_MILLIS = 16L
    }
}
