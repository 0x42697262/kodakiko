package dev.kodakiko.ui.feature.gallery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.theme.KodakikoTheme
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
class TrashDayAndSnackbarContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<GalleryAction>()

    private fun trash(state: TrashState) = compose.show { TrashContent(state = state, onAction = { actions += it }) }

    private fun day(state: DayState) = compose.show { DayContent(state = state, onAction = { actions += it }) }

    private fun gallery(snackbar: GallerySnackbar) =
        compose.show { GalleryContent(state = GalleryStates.gallery(snackbar = snackbar), onAction = { actions += it }) }

    private val trashed = TrashState(grid = GalleryStates.grid(), retentionDays = TRASH_DAYS)

    @Test
    fun theTrashShowsItsTitleRetentionBackAndTheTiles() {
        trash(trashed)
        assertTrue(compose.exists(TestTags.Trash.SCREEN))
        compose.onNodeWithTag(TestTags.Trash.TITLE).assertTextEquals("Trash")
        compose.assertTagShowsText(TestTags.Trash.RETENTION, "Entries in the trash are deleted after 30 days.")
        assertEquals(3, compose.tileCount())
        assertEquals(3, compose.headerCount())
        assertFalse(compose.exists(TestTags.Gallery.CAPTURE))
        assertFalse(compose.exists(TestTags.OnThisDay.CARD))
        assertFalse(compose.exists(TestTags.Gallery.TAG_TAB_ALL))
        compose.onNodeWithTag(TestTags.Trash.BACK).performClick()
        compose.onNodeWithTag(listItem(TestTags.Gallery.TILE, 1)).performClick()
        compose.onNodeWithTag(listItem(TestTags.Gallery.TILE, 2)).performTouchInput { longClick() }
        assertEquals(listOf(GalleryAction.Back, GalleryAction.TileClick("e2"), GalleryAction.TileLongClick("e3")), actions)
    }

    @Test
    fun aLongPullInTheTrashSyncsWhenSyncIsAvailable() {
        val tiles = listOf(GallerySection("2026-11-18", (0 until 30).map { GalleryStates.tile("m$it", it) }))
        trash(trashed.copy(grid = GalleryStates.grid(tiles), pullToSync = true))
        compose.onNodeWithTag(TestTags.PULL_TO_SYNC).performTouchInput {
            val start = Offset(centerX, top + PULL_START)
            swipe(start = start, end = start + Offset(0f, PULL_DISTANCE), durationMillis = PULL_MS)
        }
        compose.waitForIdle()
        assertEquals(1, actions.count { it == GalleryAction.PullToSync })
    }

    @Test
    fun aLongPullInADaySyncsWhenSyncIsAvailable() {
        val tiles = listOf(GallerySection("2026-11-18", (0 until 30).map { GalleryStates.tile("m$it", it) }))
        day(DayState(date = "2026-11-18", grid = GalleryStates.grid(tiles), pullToSync = true))
        compose.onNodeWithTag(TestTags.PULL_TO_SYNC).performTouchInput {
            val start = Offset(centerX, top + PULL_START)
            swipe(start = start, end = start + Offset(0f, PULL_DISTANCE), durationMillis = PULL_MS)
        }
        compose.waitForIdle()
        assertEquals(1, actions.count { it == GalleryAction.PullToSync })
    }

    @Test
    fun anEmptyTrashSaysSo() {
        trash(TrashState(grid = GalleryStates.grid(emptyList()), retentionDays = 14L))
        compose.onNodeWithTag(TestTags.Trash.EMPTY).assertTextEquals("The trash is empty.")
        compose.assertTagShowsText(TestTags.Trash.RETENTION, "Entries in the trash are deleted after 14 days.")
    }

    @Test
    fun theTrashShowsTheOpeningSpinnerWhileTheIndexBuilds() {
        trash(TrashState(opening = true, grid = GalleryStates.grid(emptyList()), retentionDays = TRASH_DAYS))
        compose.onNodeWithTag(TestTags.Gallery.OPENING).assertTextEquals("Opening your journal…")
        assertTrue(compose.exists(TestTags.Gallery.OPENING_BUSY))
        assertFalse(compose.exists(TestTags.Trash.EMPTY))
    }

    @Test
    fun selectingInTheTrashShowsTheCountCancelAndActions() {
        trash(trashed.copy(grid = GalleryStates.grid(selection = GallerySelection(active = true, ids = listOf("e1", "e3")))))
        compose.onNodeWithTag(TestTags.Gallery.SELECTED).assertTextEquals("2 selected")
        assertFalse(compose.exists(TestTags.Trash.TITLE))
        compose.onNodeWithTag(TestTags.Gallery.ACTIONS).assertIsEnabled().performClick()
        compose.onNodeWithTag(TestTags.Gallery.CANCEL_SELECTION).performClick()
        assertEquals(listOf(GalleryAction.OpenActions, GalleryAction.ClearSelection), actions)
        assertTrue(compose.exists(listItem(TestTags.Gallery.TILE_CHECKBOX, 0)))
    }

    @Test
    fun trashActionsNeedASelectedEntry() {
        trash(trashed.copy(grid = GalleryStates.grid(selection = GallerySelection(active = true, ids = emptyList()))))
        compose.onNodeWithTag(TestTags.Gallery.ACTIONS).assertIsNotEnabled()
    }

    @Test
    fun theTrashBulkSheetShowsInTheTrash() {
        trash(trashed.copy(sheet = GalleryStates.actions(mode = BulkMode.TRASH)))
        assertTrue(compose.exists(TestTags.Gallery.ACTIONS_SHEET))
        compose.onNodeWithTag(TestTags.Bulk.RESTORE).performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.BulkRestore), actions)
    }

    @Test
    fun aDayShowsItsDateAsTheTitleWithBackAndTheTiles() {
        day(DayState(date = "2026-11-17", grid = GalleryStates.grid(GalleryStates.oneDay)))
        assertTrue(compose.exists(TestTags.Day.SCREEN))
        compose.onNodeWithTag(TestTags.Day.TITLE).assertTextEquals("Tuesday, Nov 17")
        assertEquals(1, compose.tileCount())
        compose.onNodeWithTag(listItem(TestTags.Gallery.TILE, 0)).assertIsDisplayed().performClick()
        compose.onNodeWithTag(TestTags.Day.BACK).performClick()
        assertEquals(listOf(GalleryAction.TileClick("e2"), GalleryAction.Back), actions)
        assertFalse(compose.exists(TestTags.Gallery.CAPTURE))
    }

    @Test
    fun todayReadsAsAWord() {
        day(DayState(date = TODAY, grid = GalleryStates.grid(emptyList())))
        compose.onNodeWithTag(TestTags.Day.TITLE).assertTextEquals("Today")
    }

    @Test
    fun aDayFromAnotherYearShowsTheYear() {
        day(DayState(date = "2024-02-29", grid = GalleryStates.grid(emptyList())))
        compose.onNodeWithTag(TestTags.Day.TITLE).assertTextEquals("Feb 29, 2024")
    }

    @Test
    fun anEmptyDaySaysSoAndOffersBack() {
        day(DayState(date = "2026-11-03", grid = GalleryStates.grid(emptyList())))
        compose.onNodeWithTag(TestTags.Day.EMPTY).assertTextEquals("No entries on this day.")
        val backs = compose.onAllNodesWithTag(TestTags.Day.BACK).fetchSemanticsNodes()
        assertTrue(backs.isNotEmpty())
        compose.onAllNodesWithTag(TestTags.Day.BACK)[backs.size - 1].performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.Back), actions)
    }

    @Test
    fun selectingInADayShowsTheCountAndActions() {
        day(DayState(date = "2026-11-17", grid = GalleryStates.grid(selection = GallerySelection(active = true, ids = listOf("e2")))))
        compose.onNodeWithTag(TestTags.Gallery.SELECTED).assertTextEquals("1 selected")
        assertFalse(compose.exists(TestTags.Day.TITLE))
        compose.onNodeWithTag(TestTags.Gallery.ACTIONS).performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.OpenActions), actions)
    }

    @Test
    fun movedToTrashOffersAnUndoThatReportsOnce() {
        gallery(GallerySnackbar.Trashed("e2"))
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_TRASHED).assertTextEquals("Moved to trash.")
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_UNDO).assert(hasText("Undo")).performClick()
        compose.waitForIdle()
        assertEquals(1, actions.count { it == GalleryAction.UndoTrash })
    }

    @Test
    fun goneAndSavedShowTheirText() {
        gallery(GallerySnackbar.Gone)
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertTextEquals("That entry no longer exists.")
        assertFalse(compose.exists(TestTags.Gallery.NOTICE_UNDO))
    }

    @Test
    fun savedShowsSaved() {
        gallery(GallerySnackbar.Saved)
        compose.onNodeWithTag(TestTags.Entry.SAVED).assertTextEquals("Saved.")
    }

    @Test
    fun bulkResultsCountUpdatedAndSkippedEntries() {
        gallery(GallerySnackbar.BulkDone(1, 2))
        compose.onNodeWithTag(TestTags.Bulk.RESULT).assertTextEquals("1 entry updated. 2 skipped.")
    }

    @Test
    fun bulkResultsWithoutSkippedEntriesSayOnlyHowManyWereUpdated() {
        gallery(GallerySnackbar.BulkDone(2, 0))
        compose.onNodeWithTag(TestTags.Bulk.RESULT).assertTextEquals("2 entries updated.")
    }

    @Test
    fun aSnackbarLeavesAfterFourSecondsAndReportsItsDismissal() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KodakikoTheme {
                CompositionLocalProvider(LocalGalleryThumbnails provides FakeThumbnails()) {
                    GalleryContent(state = GalleryStates.gallery(snackbar = GallerySnackbar.Gone), onAction = { actions += it })
                }
            }
        }
        compose.mainClock.advanceTimeBy(SHOWN_MS)
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(STILL_SHOWN_MS)
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertIsDisplayed()
        assertFalse(actions.contains(GalleryAction.SnackbarDismissed))
        compose.mainClock.advanceTimeBy(GONE_AFTER_MS)
        compose.onNodeWithTag(TestTags.Gallery.NOTICE_GONE).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, actions.count { it == GalleryAction.SnackbarDismissed })
    }

    @Test
    fun theTrashShowsBulkResultsToo() {
        trash(trashed.copy(snackbar = GallerySnackbar.BulkDone(3, 0)))
        compose.onNodeWithTag(TestTags.Bulk.RESULT).assertTextEquals("3 entries updated.")
    }

    @Test
    fun aDayShowsBulkResultsToo() {
        day(DayState(date = "2026-11-17", grid = GalleryStates.grid(), snackbar = GallerySnackbar.BulkDone(1, 0)))
        compose.onNodeWithTag(TestTags.Bulk.RESULT).assertTextEquals("1 entry updated.")
    }

    private companion object {
        const val PULL_START = 20f
        const val PULL_DISTANCE = 400f
        const val PULL_MS = 600L
        const val SHOWN_MS = 500L
        const val STILL_SHOWN_MS = 3_300L
        const val GONE_AFTER_MS = 800L
    }
}
