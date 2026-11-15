package dev.kodakiko.ui.core.components.material

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class PullRefreshTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var refreshing by mutableStateOf(false)

    private var refreshes = 0

    private lateinit var listState: LazyListState

    private val spinning = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    private fun screen(enabled: Boolean = true) {
        compose.setContent {
            KodakikoTheme {
                listState = rememberLazyListState()
                PullRefresh(
                    refreshing = refreshing,
                    enabled = enabled,
                    onRefresh = { refreshes++ },
                    modifier = Modifier.testTag(TestTags.PULL_TO_SYNC),
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(LIST)) {
                        items(ITEM_COUNT) { index -> Text("row $index", modifier = Modifier.height(ROW_HEIGHT.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pull(distance: Float) {
        compose.onNodeWithTag(TestTags.PULL_TO_SYNC).performTouchInput {
            val start = Offset(centerX, top + 20f)
            swipe(start = start, end = start + Offset(0f, distance), durationMillis = 600)
        }
        compose.waitForIdle()
    }

    @Test
    fun theWrapperCarriesThePullToSyncTag() {
        screen()
        compose.onNodeWithTag(TestTags.PULL_TO_SYNC).assertIsDisplayed()
        compose.onNodeWithTag(LIST).assertIsDisplayed()
    }

    @Test
    fun aLongPullAtTheTopRefreshesOnce() {
        screen()
        pull(400f)
        assertEquals(1, refreshes)
    }

    @Test
    fun aShortPullDoesNotRefresh() {
        screen()
        pull(60f)
        assertEquals(0, refreshes)
    }

    @Test
    fun aDisabledPullNeverRefreshes() {
        screen(enabled = false)
        pull(400f)
        assertEquals(0, refreshes)
    }

    @Test
    fun pullingDownInsideAScrolledListScrollsTheListInstead() {
        screen()
        compose.onNodeWithTag(LIST).performScrollToIndex(30)
        compose.waitForIdle()
        val before = listState.firstVisibleItemIndex
        pull(300f)
        assertEquals(0, refreshes)
        assertTrue("list did not scroll up from $before", listState.firstVisibleItemIndex < before)
    }

    @Test
    fun theSpinnerShowsWhileRefreshing() {
        screen()
        assertEquals(0, compose.onAllNodes(spinning, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.runOnIdle { refreshing = true }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(spinning, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.runOnIdle { refreshing = false }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(spinning, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private companion object {
        const val LIST = "test.list"
        const val ITEM_COUNT = 100
        const val ROW_HEIGHT = 48f
    }
}
