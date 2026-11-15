@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.core.components.material

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.bounds
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.string
import dev.kodakiko.ui.core.theme.KodakikoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class ScaffoldSnackbarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val host = SnackbarHostState()

    private lateinit var scope: CoroutineScope

    private val results = mutableListOf<Pair<String, SnackbarResult>>()

    private val saved = SnackbarMessage(text = string(R.string.entry_saved), testTag = TestTags.Entry.SAVED)

    private val gone = SnackbarMessage(text = string(R.string.gallery_notice_gone), testTag = TestTags.Gallery.NOTICE_GONE)

    private val actionClicks = mutableListOf<String>()

    private val reset = SnackbarMessage(
        text = string(R.string.advanced_reset_done),
        testTag = TestTags.Advanced.MESSAGE,
        action = SnackbarAction(label = string(R.string.sync_retry), testTag = TestTags.Sync.RETRY, onClick = { actionClicks += "retry" }),
    )

    private fun screen() {
        compose.setContent {
            scope = rememberCoroutineScope()
            KodakikoTheme {
                Scaffold(
                    topBar = { AppBar(title = string(R.string.gallery_title), modifier = Modifier.testTag(TOP_BAR)) },
                    bottomBar = { Box(Modifier.fillMaxWidth().height(BOTTOM_BAR_HEIGHT.dp).testTag(BOTTOM_BAR)) },
                    snackbarHost = { KodakikoSnackbarHost(host) },
                ) { padding ->
                    Text(string(R.string.gallery_empty), modifier = Modifier.testTag(CONTENT))
                    contentPadding = padding
                }
            }
        }
    }

    private var contentPadding: PaddingValues? = null

    private fun show(message: SnackbarMessage) {
        compose.runOnIdle {
            scope.launch { results += message.testTag to host.showMessage(message) }
        }
    }

    @Test
    fun aSnackbarShowsItsTextUnderItsTestTagAndLeavesAfterFourSeconds() {
        screen()
        compose.mainClock.autoAdvance = false
        show(saved)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertIsDisplayed().assertTextEquals(string(R.string.entry_saved))
        compose.mainClock.advanceTimeBy(3_300)
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf(TestTags.Entry.SAVED to SnackbarResult.Dismissed), results)
    }

    @Test
    fun aNewSnackbarReplacesTheShownOneAtOnceLikeV031() {
        screen()
        compose.mainClock.autoAdvance = false
        show(saved)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertIsDisplayed()
        show(gone)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertIsDisplayed()
        show(reset)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(
            listOf(
                TestTags.Entry.SAVED to SnackbarResult.Dismissed,
                TestTags.Gallery.NOTICE_GONE to SnackbarResult.Dismissed,
                TestTags.Advanced.MESSAGE to SnackbarResult.Dismissed,
            ),
            results,
        )
        assertEquals(emptyList<String>(), actionClicks)
    }

    @Test
    fun theActionRunsOnceAndDismissesTheSnackbar() {
        screen()
        compose.mainClock.autoAdvance = false
        show(reset)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE).assertIsDisplayed().assertTextEquals(string(R.string.advanced_reset_done))
        compose.onNodeWithTag(TestTags.Sync.RETRY).assertIsDisplayed().assertTextEquals(string(R.string.sync_retry)).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(TestTags.Advanced.MESSAGE).assertDoesNotExist()
        assertEquals(listOf("retry"), actionClicks)
        assertEquals(listOf(TestTags.Advanced.MESSAGE to SnackbarResult.ActionPerformed), results)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun scaffoldPadsItsContentByTheBarsAndKeepsTheSnackbarAboveTheBottomBar() {
        screen()
        compose.mainClock.autoAdvance = false
        show(saved)
        compose.mainClock.advanceTimeBy(1_000)
        val density = compose.density
        val top = compose.onNodeWithTag(TOP_BAR).bounds()
        val bottom = compose.onNodeWithTag(BOTTOM_BAR).bounds()
        val padding = requireNotNull(contentPadding)
        val topPadding = with(density) { padding.calculateTopPadding().toPx() }
        val bottomPadding = with(density) { padding.calculateBottomPadding().toPx() }
        assertTrue("top padding $topPadding vs top bar ${top.height}", abs(topPadding - top.height) <= 1f)
        assertTrue("bottom padding $bottomPadding vs bottom bar ${bottom.height}", abs(bottomPadding - bottom.height) <= 1f)
        val snackbar = compose.onNodeWithTag(TestTags.Entry.SAVED).bounds()
        assertTrue("snackbar bottom ${snackbar.bottom} below bottom bar top ${bottom.top}", snackbar.bottom <= bottom.top + 1f)
        compose.onNodeWithTag(CONTENT).assertIsDisplayed()
    }

    private companion object {
        const val TOP_BAR = "test.topBar"
        const val BOTTOM_BAR = "test.bottomBar"
        const val CONTENT = "test.content"
        const val BOTTOM_BAR_HEIGHT = 80
    }
}
