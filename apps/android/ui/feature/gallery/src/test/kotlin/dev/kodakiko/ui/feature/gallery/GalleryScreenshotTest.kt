package dev.kodakiko.ui.feature.gallery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.GallerySort
import dev.kodakiko.data.db.GallerySortKey
import dev.kodakiko.data.db.TriState
import dev.kodakiko.ui.core.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class GalleryScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val defaults = GalleryStates.defaults

    private fun capture(name: String, appTheme: AppTheme = AppTheme.LIGHT, content: @Composable () -> Unit) {
        compose.show(appTheme) { content() }
        compose.onRoot().captureScreenshot("gallery/$name")
    }

    private fun captureSheet(name: String, tab: String? = null, content: @Composable () -> Unit) {
        compose.show { content() }
        if (tab != null) {
            compose.onNodeWithTag("${TestTags.Sheet.TAB}.$tab").performClick()
            compose.waitForIdle()
        }
        captureScreenScreenshot("gallery/$name")
    }

    @Composable
    private fun Gallery(state: GalleryState) = GalleryContent(state = state, onAction = {})

    private fun mode(mode: GalleryDisplayMode, columns: Int = 0): GalleryGrid =
        GalleryStates.grid(GalleryStates.recentDays, GalleryStates.display(mode, columnsPortrait = columns))

    @Test
    fun comfortableGrid() = capture("comfortable_grid") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.COMFORTABLE_GRID), tagTabs = GalleryStates.tabs))
    }

    @Test
    fun compactGrid() = capture("compact_grid") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.COMPACT_GRID), tagTabs = GalleryStates.tabs))
    }

    @Test
    fun photoOnlyGridWithFiveColumns() = capture("photo_grid_5_columns") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.PHOTO_GRID, columns = 5)))
    }

    @Test
    fun list() = capture("list") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.LIST)))
    }

    @Test
    fun comfortableGridDark() = capture("comfortable_grid_dark", AppTheme.DARK) {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.COMFORTABLE_GRID), tagTabs = GalleryStates.tabs, showCounts = true))
    }

    @Test
    fun countsAndOnThisDay() = capture("counts_on_this_day") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.COMFORTABLE_GRID), tagTabs = GalleryStates.tabs, showCounts = true, onThisDay = GalleryStates.onThisDay))
    }

    @Test
    fun groupedByMonth() = capture("grouped_by_month") {
        val sections = listOf(
            GallerySection("m:2026-11", GalleryStates.recentDays.flatMap { it.tiles }.take(6)),
            GallerySection("m:2025-12", listOf(GalleryStates.tile("t7", 6, "2025-12-24", "19:00", "Christmas eve"))),
        )
        Gallery(GalleryStates.gallery(grid = GalleryStates.grid(sections)))
    }

    @Test
    fun selection() = capture("selection") {
        Gallery(
            GalleryStates.gallery(
                grid = GalleryStates.grid(GalleryStates.recentDays, selection = GallerySelection(active = true, ids = listOf("t1", "t3", "t5"))),
                tagTabs = GalleryStates.tabs,
            ),
        )
    }

    @Test
    fun searchWithChips() = capture("search_chips") {
        Gallery(
            GalleryStates.gallery(
                grid = GalleryStates.grid(GalleryStates.oneDay),
                searching = true,
                searchText = "lake",
                chips = listOf(FilterChip.Tag("a"), FilterChip.Dates("2026-11-01", "2026-11-30"), FilterChip.Text("lake")),
                filterActive = true,
            ),
        )
    }

    @Test
    fun emptyJournal() = capture("empty") {
        Gallery(GalleryStates.gallery(grid = GalleryStates.grid(emptyList()), empty = GalleryEmpty.EMPTY))
    }

    @Test
    fun noMatches() = capture("no_matches") {
        Gallery(GalleryStates.gallery(grid = GalleryStates.grid(emptyList()), empty = GalleryEmpty.NO_MATCHES, filterActive = true, chips = listOf(FilterChip.Text("nothing"))))
    }

    @Test
    fun opening() = capture("opening") {
        Gallery(GalleryStates.gallery(grid = GalleryStates.grid(emptyList()), opening = true))
    }

    @Test
    fun barsHidden() = capture("bars_hidden") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.COMFORTABLE_GRID), tagTabs = GalleryStates.tabs, barsHidden = true))
    }

    @Test
    fun movedToTrashSnackbar() = capture("snackbar_trashed") {
        Gallery(GalleryStates.gallery(grid = mode(GalleryDisplayMode.COMFORTABLE_GRID), snackbar = GallerySnackbar.Trashed("t2")))
    }

    @Test
    fun filterSheet() = captureSheet("sheet_filter") {
        val filters = defaults.galleryFilters.copy(video = TriState.INCLUDE, favorite = TriState.EXCLUDE)
        Gallery(
            GalleryStates.gallery(
                sheet = GalleryStates.settingsSheet(
                    settings = defaults.copy(galleryFilters = filters),
                    draft = FilterDraft(tags = listOf("city"), from = "2026-12-01", to = "2026-11-01", problem = DateRangeProblem.START_AFTER_END),
                ),
            ),
        )
    }

    @Test
    fun filterSheetChoosingTags() = captureSheet("sheet_filter_tags") {
        Gallery(GalleryStates.gallery(sheet = GalleryStates.settingsSheet(draft = FilterDraft(tags = listOf("city"), choosingTags = true))))
    }

    @Test
    fun sortSheet() = captureSheet("sheet_sort", tab = "sort") {
        Gallery(GalleryStates.gallery(sheet = GalleryStates.settingsSheet(settings = defaults.copy(gallerySort = GallerySort(GallerySortKey.NOTE_LENGTH, descending = false)))))
    }

    @Test
    fun displaySheet() = captureSheet("sheet_display", tab = "display") {
        Gallery(GalleryStates.gallery(sheet = GalleryStates.settingsSheet(settings = defaults.copy(galleryColumnsPortrait = 4, galleryShowCounts = true))))
    }

    @Test
    fun groupSheet() = captureSheet("sheet_group", tab = "group") {
        Gallery(GalleryStates.gallery(sheet = GalleryStates.settingsSheet(group = GalleryGroup.MONTH)))
    }

    @Test
    fun bulkMenu() = captureSheet("bulk_menu") {
        Gallery(
            GalleryStates.gallery(
                grid = GalleryStates.grid(GalleryStates.recentDays, selection = GallerySelection(active = true, ids = listOf("t1", "t2"))),
                sheet = GalleryStates.actions(),
            ),
        )
    }

    @Test
    fun bulkTagPanel() = captureSheet("bulk_add_tag") {
        Gallery(GalleryStates.gallery(sheet = GalleryStates.actions(panel = BulkPanel.ADD_TAG, pickedTag = "city")))
    }

    @Test
    fun trash() = capture("trash") {
        TrashContent(state = TrashState(grid = GalleryStates.grid(GalleryStates.recentDays), retentionDays = TRASH_DAYS), onAction = {})
    }

    @Test
    fun trashEmpty() = capture("trash_empty") {
        TrashContent(state = TrashState(grid = GalleryStates.grid(emptyList()), retentionDays = TRASH_DAYS), onAction = {})
    }

    @Test
    fun trashDeleteConfirm() = captureSheet("trash_delete_confirm") {
        TrashContent(
            state = TrashState(
                grid = GalleryStates.grid(GalleryStates.recentDays, selection = GallerySelection(active = true, ids = listOf("t1", "t2"))),
                retentionDays = TRASH_DAYS,
                sheet = GalleryStates.actions(mode = BulkMode.TRASH, panel = BulkPanel.CONFIRM_DELETE),
            ),
            onAction = {},
        )
    }

    @Test
    fun day() = capture("day") {
        DayContent(state = DayState(date = "2026-11-21", grid = GalleryStates.grid(GalleryStates.recentDays.subList(0, 1))), onAction = {})
    }

    @Test
    fun dayEmpty() = capture("day_empty") {
        DayContent(state = DayState(date = "2026-11-03", grid = GalleryStates.grid(emptyList())), onAction = {})
    }
}
