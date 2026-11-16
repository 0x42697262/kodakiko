package dev.kodakiko.app.navigation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import dev.kodakiko.app.FakeAndroidKeyStore
import dev.kodakiko.app.MainActivity
import dev.kodakiko.app.UpdatedV031App
import dev.kodakiko.app.V031Fixture
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CalendarAndViewerWiringTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        FakeAndroidKeyStore.uninstall()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun awaitTag(tag: String, timeoutMs: Long = WAIT_MS) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMs)
    }

    private fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun unlock() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag(TestTags.Lock.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performScrollTo().performClick().performTextInput(V031Fixture.PASSWORD)
        compose.onNodeWithTag(TestTags.Lock.PASSWORD).performImeAction()
        awaitTag(listItem(TestTags.Gallery.TILE, 0), UNLOCK_WAIT_MS)
    }

    @Test
    @Config(application = UpdatedV031App::class)
    fun theAdoptedEntryOpensInTheViewerWithItsDetailsAndTheCalendarTabShowsTheMonth() {
        unlock()
        compose.onNodeWithTag(listItem(TestTags.Gallery.TILE, 0)).performClick()
        awaitTag(TestTags.Viewer.SCREEN)
        awaitTag(TestTags.Viewer.PAGE_INDICATOR)
        compose.onNodeWithTag(TestTags.Viewer.PAGE_INDICATOR).assertTextEquals("1 / 1")
        awaitTag(listItem(TestTags.Viewer.ZOOMABLE, 0))
        assertTrue(compose.onAllNodes(hasTestTag(TestTags.Viewer.TIME)).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag(TestTags.Viewer.DETAILS_ACTION).performScrollTo().performClick()
        awaitTag(TestTags.Details.SCREEN)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText("Photo 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        back()
        awaitTag(TestTags.Viewer.SCREEN)
        compose.onNodeWithTag(TestTags.Viewer.BACK).performClick()
        awaitTag(TestTags.Gallery.SCREEN)
        compose.onNodeWithTag(TestTags.Tabs.CALENDAR).performClick()
        awaitTag(TestTags.Calendar.SCREEN)
        awaitTag(TestTags.Calendar.MONTH_TITLE)
        assertTrue(compose.onAllNodes(hasTestTag(listItem(TestTags.Calendar.WEEKDAY, 6))).fetchSemanticsNodes().isNotEmpty())
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val UNLOCK_WAIT_MS = 120_000L
    }
}
