package dev.kodakiko.ui.feature.gallery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.GallerySort
import dev.kodakiko.data.db.GallerySortKey
import dev.kodakiko.data.db.TriState
import dev.kodakiko.index.AttributeKey
import dev.kodakiko.ui.core.R
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
@Config(qualifiers = "w360dp-h740dp-mdpi")
class GallerySheetsContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<GalleryAction>()
    private val defaults = GalleryStates.defaults

    private fun show(sheet: GallerySheet) {
        compose.show { GalleryContent(state = GalleryStates.gallery(sheet = sheet), onAction = { actions += it }) }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun tab(name: String) {
        compose.onNodeWithTag("${TestTags.Sheet.TAB}.$name").performClick()
        compose.waitForIdle()
    }

    private fun toggle(state: ToggleableState): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    private fun columns() = compose.onNode(
        (hasTestTag(TestTags.Sheet.COLUMNS) or hasAnyAncestor(hasTestTag(TestTags.Sheet.COLUMNS))) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        useUnmergedTree = true,
    )

    private fun range(value: Float): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(value, 0f..10f, 9))

    @Test
    fun theNewTagsFollowTheV031Names() {
        assertEquals("bulk.confirmAdd", TestTags.Bulk.CONFIRM_ADD)
        assertEquals("bulk.confirmRemove", TestTags.Bulk.CONFIRM_REMOVE)
        assertEquals("filter.startAfterEnd", TestTags.Filter.START_AFTER_END)
        assertEquals("filter.invalidDate", TestTags.Filter.INVALID_DATE)
        assertEquals("sheet.columns", TestTags.Sheet.COLUMNS)
    }

    @Test
    fun theSheetHasFilterSortDisplayAndGroupTabsWithFilterFirst() {
        show(GalleryStates.settingsSheet())
        assertTrue(compose.exists(TestTags.Filter.SHEET))
        val tabs = listOf("filter" to "Filter", "sort" to "Sort", "display" to "Display", "group" to "Group")
        for ((name, label) in tabs) compose.onNodeWithTag("${TestTags.Sheet.TAB}.$name").assert(hasText(label))
        compose.onNodeWithTag("${TestTags.Sheet.TAB}.filter").assertIsSelected()
        compose.onNodeWithTag("${TestTags.Sheet.TAB}.sort").assertIsNotSelected()
    }

    @Test
    fun filterRowsShowTheirTriStateAndCycleOnTap() {
        val filters = defaults.galleryFilters.copy(video = TriState.INCLUDE, favorite = TriState.EXCLUDE)
        show(GalleryStates.settingsSheet(settings = defaults.copy(galleryFilters = filters)))
        val rows = listOf(
            Triple(AttributeKey.VIDEO, "Has video", ToggleableState.On),
            Triple(AttributeKey.NOTE, "Has note", ToggleableState.Off),
            Triple(AttributeKey.MULTIPLE, "Several photos", ToggleableState.Off),
            Triple(AttributeKey.SHARED, "Shared", ToggleableState.Off),
            Triple(AttributeKey.PENDING, "Waiting to upload", ToggleableState.Off),
            Triple(AttributeKey.FAVORITE, "Favorite", ToggleableState.Indeterminate),
        )
        for ((key, label, state) in rows) {
            compose.onNodeWithTag("${TestTags.Sheet.FILTER}.${key.wire}").performScrollTo().assert(hasText(label)).assert(toggle(state)).performClick()
        }
        assertEquals(rows.map { GalleryAction.CycleFilter(it.first) }, actions)
    }

    @Test
    fun theDraftedTagsShowAsChipsThatRemoveAndChooseTagsOpensThePicker() {
        show(GalleryStates.settingsSheet(draft = FilterDraft(tags = listOf("city", "beach"))))
        node(listItem(TestTags.Tags.CHIP, 1)).performScrollTo()
        compose.assertTagShowsText(listItem(TestTags.Tags.CHIP, 0), "city")
        compose.assertTagShowsText(listItem(TestTags.Tags.CHIP, 1), "beach")
        compose.onNodeWithTag(listItem(TestTags.Tags.REMOVE, 1)).performClick()
        compose.onNodeWithTag(TestTags.Tags.CHOOSE).performScrollTo().assert(hasText("Choose tags")).performClick()
        assertEquals(listOf(GalleryAction.DraftTag("beach"), GalleryAction.ChooseTags), actions)
    }

    @Test
    fun theDateFieldsShowTheDraftAndReportTyping() {
        var sheet by mutableStateOf(GalleryStates.settingsSheet(draft = FilterDraft(from = "2026-11-01")))
        compose.show {
            GalleryContent(state = GalleryStates.gallery(sheet = sheet), onAction = { action ->
                actions += action
                if (action is GalleryAction.DraftTo) sheet = sheet.copy(draft = sheet.draft.copy(to = action.text))
            })
        }
        compose.onNodeWithTag(TestTags.Filter.FROM).performScrollTo().assert(hasText("2026-11-01"))
        compose.onNodeWithTag(TestTags.Filter.TO).performScrollTo().performTextInput("2026-11-30")
        compose.onNodeWithTag(TestTags.Filter.TO).assert(hasText("2026-11-30"))
        assertEquals(listOf<GalleryAction>(GalleryAction.DraftTo("2026-11-30")), actions)
    }

    @Test
    fun applyAndResetReportTheirTaps() {
        show(GalleryStates.settingsSheet(draft = FilterDraft(tags = listOf("city"), from = "2026-11-01")))
        compose.onNodeWithTag(TestTags.Filter.APPLY).assert(hasText("Apply")).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(TestTags.Filter.RESET).assert(hasText("Reset")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(GalleryAction.ApplyFilter, GalleryAction.ResetFilter), actions)
    }

    @Test
    fun choosingTagsListsEveryTagWithTheDraftedOnesCheckedAndEditTags() {
        show(GalleryStates.settingsSheet(draft = FilterDraft(tags = listOf("city"), choosingTags = true)))
        compose.onNodeWithTag("${TestTags.Tags.OPTION}.beach").assert(toggle(ToggleableState.Off)).performClick()
        compose.onNodeWithTag("${TestTags.Tags.OPTION}.city").assert(toggle(ToggleableState.On)).performClick()
        compose.onNodeWithTag("${TestTags.Tags.OPTION}.family").assert(hasText("family"))
        compose.onNodeWithTag(TestTags.Tags.DONE).assert(hasText("Done")).performClick()
        assertEquals(listOf(GalleryAction.DraftTag("beach"), GalleryAction.DraftTag("city"), GalleryAction.TagsDone), actions)
        actions.clear()
        compose.onNodeWithTag(TestTags.Tags.EDIT_TAGS).assert(hasText("Edit tags")).performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.EditTags), actions)
    }

    @Test
    fun aDateProblemIsShownUnderTheFields() {
        show(GalleryStates.settingsSheet(draft = FilterDraft(from = "2026-12-01", to = "2026-11-01", problem = DateRangeProblem.START_AFTER_END)))
        node(TestTags.Filter.START_AFTER_END).performScrollTo()
        compose.assertTagShowsText(TestTags.Filter.START_AFTER_END, "The start date must be before the end date.")
    }

    @Test
    fun anInvalidDateIsShownWithTheFormat() {
        show(GalleryStates.settingsSheet(draft = FilterDraft(from = "2026-1-1", problem = DateRangeProblem.INVALID_DATE)))
        node(TestTags.Filter.INVALID_DATE).performScrollTo()
        compose.assertTagShowsText(TestTags.Filter.INVALID_DATE, "Use the format 2026-11-11.")
    }

    @Test
    fun sortRowsMarkTheActiveSortAndReportTaps() {
        show(GalleryStates.settingsSheet(settings = defaults.copy(gallerySort = GallerySort(GallerySortKey.NOTE_LENGTH, descending = false))))
        tab("sort")
        val labels = mapOf(
            GallerySortKey.TAKEN_AT to "Date taken",
            GallerySortKey.NOTE_LENGTH to "Note length",
            GallerySortKey.MEDIA_COUNT to "Number of photos",
            GallerySortKey.RANDOM to "Random",
        )
        for ((key, label) in labels) {
            val row = compose.onNodeWithTag("${TestTags.Sheet.SORT}.${key.wire}").assert(hasText(label))
            if (key == GallerySortKey.NOTE_LENGTH) row.assertIsSelected() else row.assertIsNotSelected()
        }
        compose.onNodeWithTag("${TestTags.Sheet.SORT}.random").performClick()
        compose.onNodeWithTag("${TestTags.Sheet.SORT}.noteLength").performClick()
        assertEquals(listOf(GalleryAction.Sort(GallerySortKey.RANDOM), GalleryAction.Sort(GallerySortKey.NOTE_LENGTH)), actions)
    }

    @Test
    fun theDisplayTabOffersTheFourModesColumnsBadgesAndTabs() {
        show(GalleryStates.settingsSheet(settings = defaults.copy(galleryColumnsPortrait = 4, galleryShowCounts = true)))
        tab("display")
        val modes = mapOf(
            GalleryDisplayMode.COMPACT_GRID to "Compact grid",
            GalleryDisplayMode.COMFORTABLE_GRID to "Comfortable grid",
            GalleryDisplayMode.PHOTO_GRID to "Photo-only grid",
            GalleryDisplayMode.LIST to "List",
        )
        for ((mode, label) in modes) {
            val chip = compose.onNodeWithTag("${TestTags.Settings.DISPLAY_MODE}.${mode.wire}").assert(hasText(label))
            if (mode == GalleryDisplayMode.COMFORTABLE_GRID) chip.assertIsSelected() else chip.assertIsNotSelected()
        }
        compose.onNodeWithTag("${TestTags.Settings.DISPLAY_MODE}.list").performClick()
        columns().performScrollTo().assert(range(4f)).performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        val badges = listOf("favorite" to BadgeKey.FAVORITE, "video" to BadgeKey.VIDEO, "photoCount" to BadgeKey.PHOTO_COUNT, "note" to BadgeKey.NOTE, "pending" to BadgeKey.PENDING)
        for ((name, _) in badges) compose.onNodeWithTag("${TestTags.Settings.BADGE}.$name").performScrollTo().assert(toggle(ToggleableState.On))
        compose.onNodeWithTag("${TestTags.Settings.BADGE}.pending").performClick()
        compose.onNodeWithTag(TestTags.Settings.SHOW_TAG_TABS).performScrollTo().assert(hasText("Show tag tabs")).assert(toggle(ToggleableState.On)).performClick()
        compose.onNodeWithTag(TestTags.Settings.SHOW_COUNTS).performScrollTo().assert(hasText("Show number of items")).assert(toggle(ToggleableState.On)).performClick()
        assertEquals(
            listOf(
                GalleryAction.DisplayMode(GalleryDisplayMode.LIST),
                GalleryAction.Columns(landscape = false, columns = 6),
                GalleryAction.Badge(BadgeKey.PENDING, on = false),
                GalleryAction.ShowTagTabs(false),
                GalleryAction.ShowCounts(false),
            ),
            actions,
        )
    }

    @Test
    fun theColumnsReadAutoAtZeroAndHideInTheList() {
        var sheet by mutableStateOf(GalleryStates.settingsSheet())
        compose.show { GalleryContent(state = GalleryStates.gallery(sheet = sheet), onAction = { actions += it }) }
        tab("display")
        columns().performScrollTo().assert(range(0f)).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText(string(R.string.prefs_columns_auto)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        sheet = GalleryStates.settingsSheet(defaults.copy(galleryDisplayMode = GalleryDisplayMode.LIST))
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Sheet.COLUMNS))
        assertTrue(
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and hasAnyAncestor(hasTestTag(TestTags.Filter.SHEET)), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    @Config(qualifiers = "w740dp-h360dp-mdpi")
    fun inLandscapeTheColumnsAreTheLandscapeOnes() {
        show(GalleryStates.settingsSheet(settings = defaults.copy(galleryColumnsPortrait = 2, galleryColumnsLandscape = 7)))
        tab("display")
        columns().performScrollTo().assert(range(7f)).performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        assertEquals(listOf<GalleryAction>(GalleryAction.Columns(landscape = true, columns = 5)), actions)
    }

    @Test
    fun theGroupTabMarksTheCurrentGroupAndReportsTaps() {
        show(GalleryStates.settingsSheet(group = GalleryGroup.NONE))
        tab("group")
        val labels = mapOf(
            GalleryGroup.DAY to "Day",
            GalleryGroup.MONTH to "Month",
            GalleryGroup.YEAR to "Year",
            GalleryGroup.TAG to "Tag",
            GalleryGroup.NONE to "Ungrouped",
        )
        for ((group, label) in labels) {
            val row = compose.onNodeWithTag("${TestTags.Sheet.GROUP}.${group.wire}").assert(hasText(label))
            if (group == GalleryGroup.NONE) row.assertIsSelected() else row.assertIsNotSelected()
        }
        compose.onNodeWithTag("${TestTags.Sheet.GROUP}.month").performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.Group(GalleryGroup.MONTH)), actions)
    }

    @Test
    fun theJournalBulkMenuOffersTagsFavoritesTrashAndShare() {
        show(GalleryStates.actions())
        assertTrue(compose.exists(TestTags.Gallery.ACTIONS_SHEET))
        assertTrue(compose.exists(TestTags.Bulk.MENU))
        val buttons = listOf(
            Triple(TestTags.Bulk.ADD_TAG, "Add Tag…", GalleryAction.BulkAddTag),
            Triple(TestTags.Bulk.REMOVE_TAG, "Remove Tag…", GalleryAction.BulkRemoveTag),
            Triple(TestTags.Bulk.FAVORITE, "Add to Favorites", GalleryAction.BulkFavorite(true)),
            Triple(TestTags.Bulk.UNFAVORITE, "Remove from Favorites", GalleryAction.BulkFavorite(false)),
            Triple(TestTags.Bulk.TRASH, "Move to Trash", GalleryAction.BulkTrash),
            Triple(TestTags.Bulk.SHARE, "Share…", GalleryAction.BulkShare),
        )
        for ((tag, label, _) in buttons) compose.onNodeWithTag(tag).performScrollTo().assert(hasText(label)).performClick()
        assertEquals(buttons.map { it.third }, actions)
        assertFalse(compose.exists(TestTags.Bulk.RESTORE))
        assertFalse(compose.exists(TestTags.Bulk.DELETE_PERMANENTLY))
        assertFalse(compose.exists(TestTags.Bulk.RESULT))
    }

    @Test
    fun theTrashBulkMenuOffersRestoreAndDeletePermanently() {
        show(GalleryStates.actions(mode = BulkMode.TRASH))
        compose.onNodeWithTag(TestTags.Bulk.RESTORE).assert(hasText("Restore")).performClick()
        compose.onNodeWithTag(TestTags.Bulk.DELETE_PERMANENTLY).assert(hasText("Delete Permanently…")).performClick()
        assertEquals(listOf(GalleryAction.BulkRestore, GalleryAction.BulkDeletePermanently), actions)
        assertFalse(compose.exists(TestTags.Bulk.ADD_TAG))
        assertFalse(compose.exists(TestTags.Bulk.TRASH))
    }

    @Test
    fun theTagPanelPicksOneTagAndConfirmsOnlyOncePicked() {
        show(GalleryStates.actions(panel = BulkPanel.ADD_TAG))
        assertTrue(compose.exists(TestTags.Bulk.TAG_PANEL))
        compose.onNodeWithTag(TestTags.Bulk.CONFIRM_ADD).assert(hasText("Add")).assertIsNotEnabled()
        compose.onNodeWithTag("${TestTags.Bulk.TAG_OPTION}.city").assert(hasText("city")).performClick()
        compose.onNodeWithTag(TestTags.Bulk.CANCEL).assert(hasText("Cancel")).performClick()
        assertEquals(listOf(GalleryAction.BulkPickTag("city"), GalleryAction.BulkCancel), actions)
    }

    @Test
    fun aPickedTagIsMarkedAndConfirmRuns() {
        show(GalleryStates.actions(panel = BulkPanel.REMOVE_TAG, pickedTag = "beach"))
        compose.onNodeWithTag("${TestTags.Bulk.TAG_OPTION}.beach").assertIsSelected()
        compose.onNodeWithTag("${TestTags.Bulk.TAG_OPTION}.city").assertIsNotSelected()
        compose.onNodeWithTag(TestTags.Bulk.CONFIRM_REMOVE).assert(hasText("Remove")).assertIsEnabled().performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.BulkConfirmTag), actions)
    }

    @Test
    fun withoutTagsTheTagPanelSaysWhereToCreateThem() {
        show(GalleryStates.actions(panel = BulkPanel.ADD_TAG, tagChoices = emptyList()))
        assertTrue(compose.onAllNodes(hasText(string(R.string.tags_none_to_pick)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun deletingPermanentlyAsksWithTheCount() {
        show(GalleryStates.actions(mode = BulkMode.TRASH, panel = BulkPanel.CONFIRM_DELETE, count = 2))
        assertTrue(compose.exists(TestTags.Bulk.DELETE_PANEL))
        assertTrue(compose.onAllNodes(hasText("Delete 2 entries permanently? This can't be undone."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag(TestTags.Bulk.CONFIRM_DELETE).assert(hasText("Delete")).performClick()
        compose.onNodeWithTag(TestTags.Bulk.CANCEL).performClick()
        assertEquals(listOf(GalleryAction.BulkConfirmDelete, GalleryAction.BulkCancel), actions)
    }

    @Test
    fun deletingOneEntryUsesTheSingular() {
        show(GalleryStates.actions(mode = BulkMode.TRASH, panel = BulkPanel.CONFIRM_DELETE, count = 1))
        assertTrue(compose.onAllNodes(hasText("Delete 1 entry permanently? This can't be undone."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFailedBulkActionShowsTheGenericErrorInTheMenu() {
        show(GalleryStates.actions(failed = true))
        compose.assertTagShowsText(TestTags.Bulk.RESULT, string(R.string.errors_generic))
    }
}
