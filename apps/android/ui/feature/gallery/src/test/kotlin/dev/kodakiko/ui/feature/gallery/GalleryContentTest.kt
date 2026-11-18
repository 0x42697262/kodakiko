package dev.kodakiko.ui.feature.gallery

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import dev.kodakiko.data.db.GalleryDisplayMode
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
@Config(qualifiers = "w360dp-h1280dp-mdpi")
class GalleryContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<GalleryAction>()

    private fun show(state: GalleryState, thumbnails: GalleryThumbnails = FakeThumbnails()) {
        compose.show(thumbnails = thumbnails) { GalleryContent(state = state, onAction = { actions += it }) }
    }

    private fun tile(position: Int) = compose.onNodeWithTag(listItem(TestTags.Gallery.TILE, position))

    private fun top(tag: String): Dp = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().top

    private fun many(count: Int): List<GallerySection> =
        listOf(GallerySection("2026-11-18", (0 until count).map { GalleryStates.tile("m$it", it) }))

    @Test
    fun theBarShowsTheJournalTitleTheSignedInUserAndItsActions() {
        show(GalleryStates.gallery())
        assertTrue(compose.exists(TestTags.Gallery.SCREEN))
        compose.onNodeWithTag(TestTags.Gallery.TITLE).assertTextEquals("Journal")
        compose.onNodeWithTag(TestTags.Home.SIGNED_IN_AS).assertTextEquals("Signed in as alice")
        for (tag in listOf(TestTags.Gallery.SEARCH, TestTags.Gallery.FILTER, TestTags.Gallery.MORE, TestTags.Gallery.CAPTURE)) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        assertFalse(compose.exists(TestTags.Gallery.COUNT))
        assertFalse(compose.exists(TestTags.Gallery.SELECTED))
    }

    @Test
    fun theCountPillShowsTheNumberOfShownItemsWhenCountsAreOn() {
        show(GalleryStates.gallery(showCounts = true))
        compose.onNodeWithTag(TestTags.Gallery.COUNT).assertTextEquals("3")
    }

    @Test
    fun tilesAreNumberedInListOrderUnderNumberedDayHeaders() {
        show(GalleryStates.gallery())
        assertEquals(3, compose.tileCount())
        assertEquals(3, compose.headerCount())
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 0)).assertTextEquals("Wednesday, Nov 18")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 1)).assertTextEquals("Tuesday, Nov 17")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 2)).assertTextEquals("Monday, Nov 16")
        assertTrue(top(listItem(TestTags.Gallery.DAY_HEADER, 0)) < top(listItem(TestTags.Gallery.TILE, 0)))
        assertTrue(top(listItem(TestTags.Gallery.TILE, 0)) < top(listItem(TestTags.Gallery.DAY_HEADER, 1)))
        assertTrue(top(listItem(TestTags.Gallery.DAY_HEADER, 1)) < top(listItem(TestTags.Gallery.TILE, 1)))
    }

    @Test
    fun recentDaysReadTodayAndYesterdayAndOlderYearsShowTheYear() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(GalleryStates.recentDays)))
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 0)).assertTextEquals("Today")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 1)).assertTextEquals("Yesterday")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 2)).assertTextEquals("Dec 24, 2025")
    }

    @Test
    fun monthYearTagAndUntaggedGroupsGetTheirHeadersAndAnUngroupedListNone() {
        val sections = listOf(
            GallerySection("m:2026-07", listOf(GalleryStates.tile("a", 0))),
            GallerySection("y:2025", listOf(GalleryStates.tile("b", 1))),
            GallerySection("t:beach", listOf(GalleryStates.tile("c", 2))),
            GallerySection("n:", listOf(GalleryStates.tile("d", 3))),
        )
        show(GalleryStates.gallery(grid = GalleryStates.grid(sections)))
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 0)).assertTextEquals("July 2026")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 1)).assertTextEquals("2025")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 2)).assertTextEquals("#beach")
        compose.onNodeWithTag(listItem(TestTags.Gallery.DAY_HEADER, 3)).assertTextEquals("No tags")
    }

    @Test
    fun anUngroupedListHasNoHeaders() {
        val sections = listOf(GallerySection("", listOf(GalleryStates.tile("a", 0), GalleryStates.tile("b", 1))))
        show(GalleryStates.gallery(grid = GalleryStates.grid(sections)))
        assertEquals(0, compose.headerCount())
        assertEquals(2, compose.tileCount())
    }

    @Test
    fun aTapAndALongPressReportTheirEntry() {
        show(GalleryStates.gallery())
        tile(1).performClick()
        tile(0).performTouchInput { longClick() }
        assertEquals(listOf(GalleryAction.TileClick("e2"), GalleryAction.TileLongClick("e1")), actions)
    }

    @Test
    fun theComfortableGridShowsTheTimeAndTheNoteUnderTheThumbnail() {
        show(GalleryStates.gallery())
        tile(1).assert(hasText("10:00")).assert(hasText("lake day"))
        tile(2).assert(hasText("11:00"))
    }

    @Test
    fun theCompactGridOverlaysTimeAndNoteOnOneLine() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(display = GalleryStates.display(GalleryDisplayMode.COMPACT_GRID))))
        tile(1).assert(hasText("10:00 · lake day"))
        tile(2).assert(hasText("11:00"))
    }

    @Test
    fun thePhotoOnlyGridShowsNoTextAndNoBadges() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(display = GalleryStates.display(GalleryDisplayMode.PHOTO_GRID))))
        assertEquals(0, compose.onAllNodes(hasText("10:00", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("+2"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(compose.exists(listItem(TestTags.Gallery.TILE_PENDING, 1)))
    }

    @Test
    fun theListShowsDateAndTimeTheNoteTheTagsAndTheBadges() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(display = GalleryStates.display(GalleryDisplayMode.LIST))))
        tile(1).assert(hasText("2026-11-17 10:00")).assert(hasText("lake day")).assert(hasText("#a #b"))
        assertTrue(compose.exists(listItem(TestTags.Gallery.TILE_PENDING, 1)))
        compose.assertTagShowsText(listItem(TestTags.Gallery.TILE, 1), "+2")
    }

    @Test
    fun badgesFollowTheirSettings() {
        show(GalleryStates.gallery())
        compose.onNodeWithTag(listItem(TestTags.Gallery.TILE_PENDING, 1), useUnmergedTree = true)
            .assertContentDescriptionEquals(string(R.string.gallery_not_synced))
        assertFalse(compose.exists(listItem(TestTags.Gallery.TILE_PENDING, 0)))
        compose.assertTagShowsText(listItem(TestTags.Gallery.TILE, 1), "+2")
    }

    @Test
    fun turnedOffBadgesAreNotShown() {
        val badges = GalleryStates.defaults.galleryBadges.copy(pending = false, photoCount = false)
        show(GalleryStates.gallery(grid = GalleryStates.grid(display = GalleryStates.display(badges = badges))))
        assertFalse(compose.exists(listItem(TestTags.Gallery.TILE_PENDING, 1)))
        assertEquals(0, compose.onAllNodes(hasText("+2"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnreadableEntryShowsTheLockedTileWithoutAskingForAThumbnail() {
        val thumbnails = FakeThumbnails()
        show(GalleryStates.gallery(grid = GalleryStates.grid(GalleryStates.recentDays)), thumbnails)
        compose.assertTagShowsText(TestTags.Gallery.TILE_UNREADABLE, string(R.string.gallery_cant_open))
        assertEquals(1, compose.count(TestTags.Gallery.TILE_UNREADABLE))
        assertFalse(thumbnails.requested.any { it.first == "t4" })
        assertTrue(thumbnails.requested.contains("t1" to "media-t1"))
    }

    @Test
    fun aThumbnailThatIsNotThereYetOrAMissingCoverShowsThePlaceholder() {
        val noCover = GallerySection("2026-11-15", listOf(GalleryStates.tile("e4", 3, "2026-11-15", coverMediaId = null)))
        val thumbnails = FakeThumbnails(missing = setOf("media-e1"))
        show(GalleryStates.gallery(grid = GalleryStates.grid(GalleryStates.threeDays + noCover)), thumbnails)
        assertEquals(2, compose.count(TestTags.Gallery.THUMBNAIL_PLACEHOLDER))
        assertFalse(thumbnails.requested.any { it.first == "e4" })
        assertTrue(thumbnails.requested.containsAll(listOf("e1" to "media-e1", "e2" to "media-e2", "e3" to "media-e3")))
    }

    @Test
    fun theColumnSettingSetsTheTilesPerRow() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(6), GalleryStates.display(columnsPortrait = 4))))
        val firstRow = top(listItem(TestTags.Gallery.TILE, 0))
        for (position in 1..3) assertEquals(firstRow, top(listItem(TestTags.Gallery.TILE, position)))
        assertTrue(top(listItem(TestTags.Gallery.TILE, 4)) > firstRow)
    }

    @Test
    fun autoColumnsFitThreeTilesOnANarrowPhone() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(6))))
        val firstRow = top(listItem(TestTags.Gallery.TILE, 0))
        assertEquals(firstRow, top(listItem(TestTags.Gallery.TILE, 2)))
        assertTrue(top(listItem(TestTags.Gallery.TILE, 3)) > firstRow)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun landscapeUsesTheLandscapeColumns() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(8), GalleryStates.display(columnsPortrait = 2, columnsLandscape = 6))))
        val firstRow = top(listItem(TestTags.Gallery.TILE, 0))
        assertEquals(firstRow, top(listItem(TestTags.Gallery.TILE, 5)))
        assertTrue(top(listItem(TestTags.Gallery.TILE, 6)) > firstRow)
    }

    @Test
    fun theListPutsOneEntryPerRow() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(3), GalleryStates.display(GalleryDisplayMode.LIST, columnsPortrait = 4))))
        assertTrue(top(listItem(TestTags.Gallery.TILE, 1)) > top(listItem(TestTags.Gallery.TILE, 0)))
    }

    @Test
    fun selectionShowsTheCountTheCheckboxesAndTheSelectionActions() {
        show(
            GalleryStates.gallery(
                grid = GalleryStates.grid(selection = GallerySelection(active = true, ids = listOf("e2", "e3"))),
                searching = true,
                tagTabs = GalleryStates.tabs,
            ),
        )
        compose.onNodeWithTag(TestTags.Gallery.SELECTED).assertTextEquals("2 selected")
        assertFalse(compose.exists(TestTags.Gallery.TITLE))
        assertFalse(compose.exists(TestTags.Gallery.CAPTURE))
        assertFalse(compose.exists(TestTags.Gallery.SEARCH_FIELD))
        for (position in 0..2) assertTrue(compose.exists(listItem(TestTags.Gallery.TILE_CHECKBOX, position)))
        tile(0).assertIsNotSelected()
        tile(1).assertIsSelected()
        tile(2).assertIsSelected()
        compose.onNodeWithTag(TestTags.Gallery.SELECT_ALL).performClick()
        compose.onNodeWithTag(TestTags.Gallery.SELECT_INVERSE).performClick()
        compose.onNodeWithTag(TestTags.Gallery.ACTIONS).performClick()
        compose.onNodeWithTag(TestTags.Gallery.CANCEL_SELECTION).performClick()
        assertEquals(
            listOf(GalleryAction.SelectAll, GalleryAction.InvertSelection, GalleryAction.OpenActions, GalleryAction.ClearSelection),
            actions,
        )
    }

    @Test
    fun theSelectInverseTagIsNew() {
        assertEquals("gallery.selectInverse", TestTags.Gallery.SELECT_INVERSE)
    }

    @Test
    fun withoutSelectionThereAreNoCheckboxes() {
        show(GalleryStates.gallery())
        assertFalse(compose.exists(listItem(TestTags.Gallery.TILE_CHECKBOX, 0)))
        tile(0).assertIsNotSelected()
    }

    @Test
    fun theSearchButtonTogglesAndTheFieldReportsTyping() {
        show(GalleryStates.gallery())
        assertFalse(compose.exists(TestTags.Gallery.SEARCH_FIELD))
        compose.onNodeWithTag(TestTags.Gallery.SEARCH).performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.ToggleSearch), actions)
    }

    @Test
    fun theOpenSearchFieldShowsTheTextAndReportsChanges() {
        var state by mutableStateOf(GalleryStates.gallery(searching = true))
        compose.show {
            GalleryContent(state = state, onAction = { action ->
                actions += action
                if (action is GalleryAction.SearchText) state = state.copy(searchText = action.text)
            })
        }
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assertIsDisplayed().performTextInput("lake")
        compose.waitForIdle()
        assertEquals(GalleryAction.SearchText("lake"), actions.filterIsInstance<GalleryAction.SearchText>().last())
        compose.onNodeWithTag(TestTags.Gallery.SEARCH_FIELD).assert(hasText("lake"))
    }

    @Test
    fun chipsShowEachFilterWithARemoveButtonAndClearAll() {
        val chips = listOf(
            FilterChip.Tag("a"),
            FilterChip.Dates("2026-11-01", "2026-11-30"),
            FilterChip.Dates("2026-11-01", null),
            FilterChip.Dates(null, "2026-11-30"),
            FilterChip.Text("lake"),
        )
        show(GalleryStates.gallery(chips = chips, filterActive = true))
        val labels = listOf("a", "2026-11-01 – 2026-11-30", "From 2026-11-01", "Until 2026-11-30", "\u201clake\u201d")
        labels.forEachIndexed { position, label ->
            compose.assertTagShowsText(listItem(TestTags.Gallery.FILTER_CHIP, position), label)
            compose.onNodeWithTag(listItem(TestTags.Gallery.FILTER_CHIP_REMOVE, position)).assertContentDescriptionEquals("Remove filter $label")
        }
        compose.onNodeWithTag(listItem(TestTags.Gallery.FILTER_CHIP_REMOVE, 1)).performClick()
        compose.onNodeWithTag(TestTags.Gallery.CLEAR_ALL).assertTextEquals("Clear All").performClick()
        assertEquals(listOf(GalleryAction.RemoveChip(chips[1]), GalleryAction.ClearAll), actions)
    }

    @Test
    fun withoutChipsThereIsNoClearAll() {
        show(GalleryStates.gallery(filterActive = true))
        assertFalse(compose.exists(TestTags.Gallery.CLEAR_ALL))
        assertFalse(compose.exists(listItem(TestTags.Gallery.FILTER_CHIP, 0)))
    }

    @Test
    fun theFilterButtonSaysWhetherFiltersAreActiveAndOpensTheSheet() {
        var state by mutableStateOf(GalleryStates.gallery())
        compose.show { GalleryContent(state = state, onAction = { actions += it }) }
        compose.onNodeWithTag(TestTags.Gallery.FILTER).assertContentDescriptionEquals("Filter").performClick()
        state = GalleryStates.gallery(filterActive = true)
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Gallery.FILTER).assertContentDescriptionEquals("Filter, filters active")
        assertEquals(listOf<GalleryAction>(GalleryAction.OpenSheet), actions)
    }

    @Test
    fun theMoreMenuOffersRandomLockTrashAndSignOut() {
        show(GalleryStates.gallery())
        val items = listOf(
            TestTags.Gallery.RANDOM to GalleryAction.Random,
            TestTags.Lock.LOCK_NOW to GalleryAction.LockNow,
            TestTags.Gallery.TRASH to GalleryAction.OpenTrash,
            TestTags.Home.SIGN_OUT to GalleryAction.SignOut,
        )
        val texts = listOf("Open random entry", "Lock Now", "Trash", "Sign Out")
        items.forEachIndexed { index, (tag, action) ->
            compose.onNodeWithTag(TestTags.Gallery.MORE).performClick()
            compose.onNodeWithTag(tag).assert(hasText(texts[index])).performClick()
            compose.waitForIdle()
            assertEquals(action, actions.last())
        }
        assertEquals(items.map { it.second }, actions)
    }

    @Test
    fun theRoundButtonOpensTheCamera() {
        show(GalleryStates.gallery())
        compose.onNodeWithTag(TestTags.Gallery.CAPTURE).assertContentDescriptionEquals("Capture").performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.Capture), actions)
    }

    @Test
    fun tagTabsShowAllFavoritesAndTheTagsWithTheSelectedOneMarked() {
        show(GalleryStates.gallery(tagTabs = GalleryStates.tabs))
        compose.onNodeWithTag(TestTags.Gallery.TAG_TAB_ALL).assertIsSelected().assert(hasText("All"))
        compose.onNodeWithTag(TestTags.Gallery.TAG_TAB_FAVORITES).assertIsNotSelected().assert(hasText("Favorites"))
        compose.onNodeWithTag(listItem(TestTags.Gallery.TAG_TAB, 0)).assert(hasText("family"))
        compose.onNodeWithTag(listItem(TestTags.Gallery.TAG_TAB, 1)).assert(hasText("holiday"))
        compose.onNodeWithTag(listItem(TestTags.Gallery.TAG_TAB, 2)).assert(hasText("walk")).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.Gallery.TAG_TAB_FAVORITES).performScrollTo().performClick()
        assertEquals(listOf(GalleryAction.SelectTab(GalleryStates.tabs[4]), GalleryAction.SelectTab(GalleryStates.tabs[1])), actions)
        assertEquals(0, compose.onAllNodes(hasText("7"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun tagTabsCarryCountPillsWhenCountsAreOn() {
        show(GalleryStates.gallery(tagTabs = GalleryStates.tabs, showCounts = true))
        compose.assertTagShowsText(TestTags.Gallery.TAG_TAB_ALL, "7")
        compose.assertTagShowsText(TestTags.Gallery.TAG_TAB_FAVORITES, "2")
        compose.assertTagShowsText(listItem(TestTags.Gallery.TAG_TAB, 0), "2")
    }

    @Test
    fun withoutTabsThereIsNoTabRow() {
        show(GalleryStates.gallery())
        assertFalse(compose.exists(TestTags.Gallery.TAG_TAB_ALL))
    }

    @Test
    fun hiddenBarsHideTheTitleAndTheTagTabsButNeverTheSelectionBar() {
        var state by mutableStateOf(GalleryStates.gallery(tagTabs = GalleryStates.tabs, barsHidden = true))
        compose.show { GalleryContent(state = state, onAction = { actions += it }) }
        assertFalse(compose.exists(TestTags.Gallery.TITLE))
        assertFalse(compose.exists(TestTags.Gallery.TAG_TAB_ALL))
        assertEquals(3, compose.tileCount())
        state = state.copy(grid = GalleryStates.grid(selection = GallerySelection(active = true, ids = listOf("e1"))))
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Gallery.SELECTED).assertTextEquals("1 selected")
        assertFalse(compose.exists(TestTags.Gallery.TAG_TAB_ALL))
    }

    @Test
    fun scrollingTheListReportsHowFarItScrolled() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(90))))
        compose.onNodeWithTag(TestTags.PULL_TO_SYNC).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val offsets = actions.filterIsInstance<GalleryAction.Scrolled>().map { it.y }
        assertTrue("no scroll reported: $actions", offsets.any { it > GalleryBars.SCROLL_HIDE_THRESHOLD })
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun whileOpeningTheJournalASpinnerAndTextShow() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(emptyList()), opening = true))
        compose.onNodeWithTag(TestTags.Gallery.OPENING).assertTextEquals("Opening your journal…")
        assertTrue(compose.exists(TestTags.Gallery.OPENING_BUSY))
        assertEquals(0, compose.tileCount())
        assertFalse(compose.exists(TestTags.Gallery.EMPTY))
    }

    @Test
    fun anEmptyJournalInvitesTheFirstCapture() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(emptyList()), empty = GalleryEmpty.EMPTY))
        compose.onNodeWithTag(TestTags.Gallery.EMPTY).assertTextEquals("No entries yet. Tap + to capture your first moment.")
        assertFalse(compose.exists(TestTags.Gallery.CLEAR_FILTERS))
        assertTrue(compose.exists(TestTags.Gallery.CAPTURE))
    }

    @Test
    fun noMatchesOffersClearFilters() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(emptyList()), empty = GalleryEmpty.NO_MATCHES, filterActive = true))
        compose.onNodeWithTag(TestTags.Gallery.NO_MATCHES).assertTextEquals("No entries match these filters.")
        compose.onNodeWithTag(TestTags.Gallery.CLEAR_FILTERS).assertTextEquals("Clear Filters").performClick()
        assertEquals(listOf<GalleryAction>(GalleryAction.ClearFilters), actions)
    }

    @Test
    fun theOnThisDayCardComesFirstWithYearGroupsAndTilesNumberedAcrossThem() {
        val thumbnails = FakeThumbnails()
        show(GalleryStates.gallery(onThisDay = GalleryStates.onThisDay), thumbnails)
        compose.assertTagShowsText(TestTags.OnThisDay.TITLE, "On this day")
        compose.assertTagShowsText(listItem(TestTags.OnThisDay.GROUP, 0), "1 year ago")
        compose.assertTagShowsText(listItem(TestTags.OnThisDay.GROUP, 1), "2 years ago")
        assertEquals(3, compose.tagCount(Regex("^onThisDay\\.tile\\.\\d+$")))
        assertTrue(top(TestTags.OnThisDay.CARD) < top(listItem(TestTags.Gallery.DAY_HEADER, 0)))
        assertTrue(thumbnails.requested.contains("y2" to "media-y2"))
        assertFalse(thumbnails.requested.any { it.first == "y3" })
        compose.onNodeWithTag(listItem(TestTags.OnThisDay.TILE, 2), useUnmergedTree = true).performClick()
        compose.onNodeWithTag(listItem(TestTags.OnThisDay.TILE, 1), useUnmergedTree = true).performClick()
        assertEquals(listOf(GalleryAction.OpenEntry("y3"), GalleryAction.OpenEntry("y2")), actions)
    }

    @Test
    fun withoutOnThisDayEntriesThereIsNoCard() {
        show(GalleryStates.gallery())
        assertFalse(compose.exists(TestTags.OnThisDay.CARD))
    }

    @Test
    fun aLongPullAtTheTopSyncsWhenSyncIsAvailable() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(30))))
        pull()
        assertEquals(1, actions.count { it == GalleryAction.PullToSync })
    }

    @Test
    fun pullingDoesNothingWhenSyncIsUnavailable() {
        show(GalleryStates.gallery(grid = GalleryStates.grid(many(30)), pullToSync = false))
        pull()
        assertEquals(0, actions.count { it == GalleryAction.PullToSync })
    }

    private fun pull() {
        compose.onNodeWithTag(TestTags.PULL_TO_SYNC).performTouchInput {
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
