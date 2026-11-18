package dev.kodakiko.ui.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1280dp-mdpi")
class CalendarContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<CalendarAction>()

    private fun show(state: CalendarState, thumbnails: CalendarThumbnails = FakeCalendarThumbnails()) {
        compose.show(thumbnails = thumbnails) { CalendarContent(state = state, onAction = { actions += it }) }
    }

    private fun day(date: String) = compose.onNodeWithTag(TestTags.calendarDay(date))

    private fun shown(tag: String): Boolean = compose.exists(tag) && runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    @Test
    fun theHeaderShowsTheMonthTitleAndItsButtons() {
        show(CalendarStates.state())
        assertTrue(compose.exists(TestTags.Calendar.SCREEN))
        compose.onNodeWithTag(TestTags.Calendar.MONTH_TITLE).assertTextEquals("November 2026")
        for (tag in listOf(TestTags.Calendar.PREVIOUS, TestTags.Calendar.NEXT, TestTags.Calendar.TODAY, TestTags.Calendar.JUMP)) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag(TestTags.Calendar.TODAY).assertTextContains("Today")
        compose.onNodeWithTag(TestTags.Calendar.JUMP).assertTextContains("Jump to…")
        assertTrue(compose.exists(TestTags.Calendar.PAGER))
    }

    @Test
    fun theButtonsSendTheirActions() {
        show(CalendarStates.state(canGoNext = true))
        compose.onNodeWithTag(TestTags.Calendar.PREVIOUS).performClick()
        compose.onNodeWithTag(TestTags.Calendar.NEXT).performClick()
        compose.onNodeWithTag(TestTags.Calendar.TODAY).performClick()
        compose.onNodeWithTag(TestTags.Calendar.JUMP).performClick()
        assertEquals(listOf(CalendarAction.Previous, CalendarAction.Next, CalendarAction.Today, CalendarAction.OpenJump), actions)
    }

    @Test
    fun nextIsDisabledAfterTheLastMonthThatCanBeShown() {
        show(CalendarStates.state(canGoNext = false))
        compose.onNodeWithTag(TestTags.Calendar.NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(TestTags.Calendar.NEXT).performClick()
        assertFalse(CalendarAction.Next in actions)
        compose.onNodeWithTag(TestTags.Calendar.PREVIOUS).assertIsEnabled()
    }

    @Test
    fun theWeekdayRowStartsOnMonday() {
        show(CalendarStates.state())
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        names.forEachIndexed { position, name -> compose.onNodeWithTag(listItem(TestTags.Calendar.WEEKDAY, position)).assertTextEquals(name) }
        assertFalse(compose.exists(listItem(TestTags.Calendar.WEEKDAY, names.size)))
    }

    @Test
    fun everyDayOfTheShownMonthHasACellAndNeighborDaysAreNotTappableCells() {
        show(CalendarStates.state(days = CalendarStates.november))
        for (dayOfMonth in 1..30) assertTrue("day $dayOfMonth", shown(TestTags.calendarDay("2026-11-%02d".format(dayOfMonth))))
        for (neighbor in listOf("2026-10-26", "2026-10-30", "2026-10-31", "2026-12-01", "2026-12-06")) {
            assertFalse(neighbor, shown(TestTags.calendarDay(neighbor)))
        }
    }

    @Test
    fun cellsReadAsTheDateAndTheEntryCount() {
        show(CalendarStates.state(days = CalendarStates.november))
        day("2026-11-09").assert(hasContentDescription("November 9, 2026, 3 entries"))
        day("2026-11-17").assert(hasContentDescription("November 17, 2026, 1 entry"))
        day("2026-11-10").assert(hasContentDescription("November 10, 2026, no entries"))
    }

    @Test
    fun eachCellShowsItsDayNumber() {
        show(CalendarStates.state(days = CalendarStates.november))
        for ((date, number) in listOf("2026-11-01" to "1", "2026-11-09" to "9", "2026-11-30" to "30")) {
            compose.onNode(hasTestTag(TestTags.Calendar.DAY_NUMBER) and hasAnyAncestor(hasTestTag(TestTags.calendarDay(date))), useUnmergedTree = true)
                .assertTextEquals(number)
        }
    }

    @Test
    fun daysWithEntriesOpenAndEmptyDaysAreDisabled() {
        show(CalendarStates.state(days = CalendarStates.november))
        day("2026-11-17").assertIsEnabled()
        day("2026-11-10").assertIsNotEnabled()
        day("2026-11-10").performClick()
        day("2026-11-17").performClick()
        assertEquals(listOf<CalendarAction>(CalendarAction.OpenDay("2026-11-17")), actions)
    }

    @Test
    fun daysWithEntriesLoadTheirCoverThumbnails() {
        val thumbnails = FakeCalendarThumbnails()
        show(CalendarStates.state(days = CalendarStates.november), thumbnails)
        compose.waitForIdle()
        assertTrue(thumbnails.requested.containsAll(listOf("b" to "b-media", "n" to "n-media", "s" to "s-media", "t" to "t-media")))
    }

    @Test
    fun aDayWhoseCoverHasNoMediaShowsOnlyItsNumber() {
        val thumbnails = FakeCalendarThumbnails()
        show(CalendarStates.state(days = mapOf("2026-11-05" to CalendarDay(1, "bare", null))), thumbnails)
        day("2026-11-05").assertIsEnabled()
        assertFalse(thumbnails.requested.any { it.first == "bare" })
    }

    @Test
    fun swipingThePagerToThePreviousMonthReportsTheSettledMonth() {
        show(CalendarStates.state())
        compose.onNodeWithTag(TestTags.Calendar.PAGER).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue(actions.toString(), CalendarAction.Settled("2026-10") in actions)
    }

    @Test
    fun swipingPastTheLastPageReportsNoOtherMonth() {
        show(CalendarStates.state())
        compose.onNodeWithTag(TestTags.Calendar.PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(actions.toString(), actions.filterIsInstance<CalendarAction.Settled>().all { it.month == "2026-11" })
    }

    @Test
    fun thePagerFollowsTheShownMonth() {
        var state by mutableStateOf(CalendarStates.state())
        compose.show { CalendarContent(state = state, onAction = { actions += it }) }
        assertTrue(shown(TestTags.calendarDay("2026-11-15")))
        state = state.copy(month = "2026-09")
        compose.waitForIdle()
        assertTrue(shown(TestTags.calendarDay("2026-09-15")))
        assertFalse(shown(TestTags.calendarDay("2026-11-15")))
        compose.onNodeWithTag(TestTags.Calendar.MONTH_TITLE).assertTextEquals("September 2026")
    }

    @Test
    fun theJumpSheetShowsTheShownYearAndAMonthTapJumpsThere() {
        show(CalendarStates.state(jump = CalendarJump("2026-11")))
        assertTrue(compose.exists(TestTags.Calendar.JUMP_SHEET))
        compose.onNodeWithTag(TestTags.Calendar.JUMP_YEAR, useUnmergedTree = true).assertTextContains("2026")
        compose.onNodeWithTag(TestTags.Calendar.JUMP_PREVIOUS_YEAR).performClick()
        compose.onNodeWithTag(TestTags.Calendar.JUMP_YEAR, useUnmergedTree = true).assertTextContains("2025")
        compose.onNodeWithTag(TestTags.listItem(TestTags.Calendar.JUMP_MONTH, 1)).performClick()
        assertEquals(listOf(CalendarAction.JumpText("2025-02"), CalendarAction.SubmitJump), actions)
        assertFalse(compose.exists(TestTags.Calendar.JUMP_INVALID))
    }

    @Test
    fun theNextYearButtonMovesTheMonthGridForward() {
        show(CalendarStates.state(jump = CalendarJump("2026-11")))
        compose.onNodeWithTag(TestTags.Calendar.JUMP_NEXT_YEAR).performClick()
        compose.onNodeWithTag(TestTags.listItem(TestTags.Calendar.JUMP_MONTH, 0)).performClick()
        assertEquals(listOf(CalendarAction.JumpText("2027-01"), CalendarAction.SubmitJump), actions)
    }

    @Test
    fun jumpErrorsReadAsV031() {
        var state by mutableStateOf(CalendarStates.state(jump = CalendarJump("2026-13", CalendarJumpError.INVALID)))
        compose.show { CalendarContent(state = state, onAction = { actions += it }) }
        compose.onNodeWithTag(TestTags.Calendar.JUMP_INVALID, useUnmergedTree = true).assertTextEquals("Use the format 2026-11.")
        state = state.copy(jump = CalendarJump("2026-12", CalendarJumpError.EMPTY))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Calendar.JUMP_INVALID, useUnmergedTree = true).assertTextEquals("No entries in that month yet.")
    }

    @Test
    fun noJumpSheetWithoutAJumpState() {
        show(CalendarStates.state())
        assertFalse(compose.exists(TestTags.Calendar.JUMP_SHEET))
        assertFalse(compose.exists(TestTags.Calendar.JUMP_YEAR))
    }

    @Test
    fun aLongPullSyncsWhenSyncIsAvailable() {
        show(CalendarStates.state())
        pull()
        assertEquals(1, actions.count { it == CalendarAction.PullToSync })
    }

    @Test
    fun pullingDoesNothingWhenSyncIsUnavailable() {
        show(CalendarStates.state(pullToSync = false))
        pull()
        assertEquals(0, actions.count { it == CalendarAction.PullToSync })
    }

    private fun pull() {
        val target = if (compose.exists(TestTags.PULL_TO_SYNC)) TestTags.PULL_TO_SYNC else TestTags.Calendar.SCREEN
        compose.onNodeWithTag(target).performTouchInput {
            val start = Offset(centerX, top + PULL_START)
            swipe(start = start, end = start + Offset(0f, PULL_DISTANCE), durationMillis = PULL_MS)
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PULL_START = 20f
        const val PULL_DISTANCE = 400f
        const val PULL_MS = 600L
    }
}
