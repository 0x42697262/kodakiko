package dev.kodakiko.ui.feature.gallery

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.GallerySort
import dev.kodakiko.data.db.TimeFormat
import dev.kodakiko.data.db.TriState
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.index.AttributeKey
import dev.kodakiko.index.sortEntryIds
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.entries.BulkResult
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import dev.kodakiko.data.db.GallerySortKey as SortKey
import dev.kodakiko.index.GallerySort as IndexSort
import dev.kodakiko.index.GallerySortKey as IndexSortKey

@RunWith(RobolectricTestRunner::class)
class GalleryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = Harness()
    private val defaults: DeviceSettings = DeviceSettingsDefaults.ANDROID

    private val walk = entry("e1", "2026-11-18T09:00:00.000Z", tags = listOf("a"), note = "morning walk")
    private val lake = entry("e2", "2026-11-17T10:00:00.000Z", tags = listOf("a", "b"), note = "lake day")
    private val city = entry("e3", "2026-11-16T11:00:00.000Z", tags = listOf("c"))

    private fun TestScope.open(): Pair<GalleryViewModel, Observed<GalleryState, GalleryEvent>> {
        val vm = GalleryViewModel(h.deps)
        return vm to observe(vm.state, vm.events)
    }

    private fun TestScope.openWithThree(): Pair<GalleryViewModel, Observed<GalleryState, GalleryEvent>> {
        h.index.put(walk, lake, city)
        return open()
    }

    private val GalleryState.ids: List<String> get() = grid.sections.flatMap { section -> section.tiles.map { it.entryId } }

    private val GalleryState.keys: List<String> get() = grid.sections.map { it.key }

    private val GalleryState.positions: List<Int> get() = grid.sections.flatMap { section -> section.tiles.map { it.position } }

    private fun GalleryState.tile(entryId: String): GalleryTile = grid.sections.flatMap { it.tiles }.single { it.entryId == entryId }

    private val GalleryState.bulk: BulkSheet get() = (sheet as GallerySheet.Actions).bulk

    private val GalleryState.settingsSheet: GallerySheet.Settings get() = sheet as GallerySheet.Settings

    @Test
    fun whileTheIndexBuildsTheGalleryIsOpeningAndThenShowsTheEntries() = runTest {
        h.index.setStatus(IndexStatus.BUILDING)
        val (_, screen) = open()
        assertTrue(screen.value.opening)
        assertEquals(emptyList<GallerySection>(), screen.value.grid.sections)
        assertNull(screen.value.empty)
        h.index.put(walk, lake, city)
        h.index.setStatus(IndexStatus.READY)
        assertFalse(screen.value.opening)
        assertEquals(listOf("e1", "e2", "e3"), screen.value.ids)
    }

    @Test
    fun showsTheSignedInUsername() = runTest {
        val (_, screen) = openWithThree()
        assertEquals("alice", screen.value.username)
        h.account.usernameState.value = "bob"
        assertEquals("bob", screen.value.username)
    }

    @Test
    fun groupsEntriesByDayNewestFirstWithRunningListPositions() = runTest {
        h.index.put(walk, lake, city, entry("e4", "2026-11-17T08:00:00.000Z"))
        val (_, screen) = open()
        assertEquals(listOf("2026-11-18", "2026-11-17", "2026-11-16"), screen.value.keys)
        assertEquals(listOf("e1", "e2", "e4", "e3"), screen.value.ids)
        assertEquals(listOf(0, 1, 2, 3), screen.value.positions)
        assertEquals(TODAY, screen.value.grid.today)
    }

    @Test
    fun aTileCarriesItsCoverTimeNoteTagsAndBadgeFacts() = runTest {
        h.index.put(
            entry("e1", "2026-11-18T09:05:00.000Z", tags = listOf("a", "b"), note = "lake day", mediaCount = 3, hasVideo = true, pending = true, favorite = true),
            entry("e2", "2026-11-17T10:00:00.000Z", unreadable = true, coverMediaId = null),
        )
        val (_, screen) = open()
        assertEquals(
            GalleryTile(
                entryId = "e1",
                position = 0,
                coverMediaId = "media-e1",
                unreadable = false,
                pending = true,
                favorite = true,
                hasVideo = true,
                hasNote = true,
                extraMedia = 2,
                localDate = "2026-11-18",
                time = "09:05",
                note = "lake day",
                tags = listOf("a", "b"),
            ),
            screen.value.tile("e1"),
        )
        val unreadable = screen.value.tile("e2")
        assertTrue(unreadable.unreadable)
        assertNull(unreadable.coverMediaId)
        assertFalse(unreadable.hasNote)
        assertEquals(0, unreadable.extraMedia)
        assertFalse(unreadable.favorite)
    }

    @Test
    fun theTimeIsTheEntrysOwnLocalTimeInTheChosenClockFormat() = runTest {
        h.index.put(entry("e1", "2026-11-18T21:30:00.000Z", utcOffsetMinutes = 60))
        val (_, screen) = open()
        assertEquals("22:30", screen.value.tile("e1").time)
        h.settings.value = defaults.copy(timeFormat = TimeFormat.HOURS_12)
        assertEquals("10:30 PM", screen.value.tile("e1").time)
        assertEquals("2026-11-18", screen.value.tile("e1").localDate)
    }

    @Test
    fun notePreviewsLoseTheirMarkdownLeadAndHideWithTheSettingWhileTheNoteBadgeStays() = runTest {
        h.index.put(
            entry("e1", "2026-11-18T09:00:00.000Z", note = "## Lake day"),
            entry("e2", "2026-11-17T09:00:00.000Z", note = "> - quoted *walk*"),
        )
        val (_, screen) = open()
        assertEquals("Lake day", screen.value.tile("e1").note)
        assertEquals("quoted *walk*", screen.value.tile("e2").note)
        h.settings.value = defaults.copy(hideNotePreviews = true)
        assertEquals("", screen.value.tile("e1").note)
        assertTrue(screen.value.tile("e1").hasNote)
    }

    @Test
    fun theDisplayFollowsTheDeviceSettingsInEveryMode() = runTest {
        val (_, screen) = openWithThree()
        assertEquals(GalleryDisplay(GalleryDisplayMode.COMFORTABLE_GRID, 0, 0, defaults.galleryBadges), screen.value.grid.display)
        for (mode in GalleryDisplayMode.entries) {
            val badges = defaults.galleryBadges.copy(pending = mode != GalleryDisplayMode.LIST)
            h.settings.value = defaults.copy(galleryDisplayMode = mode, galleryColumnsPortrait = 4, galleryColumnsLandscape = 7, galleryBadges = badges)
            assertEquals(GalleryDisplay(mode, 4, 7, badges), screen.value.grid.display)
            assertEquals(listOf("e1", "e2", "e3"), screen.value.ids)
        }
    }

    @Test
    fun groupsByMonthNewestFirstAfterTheGroupChoice() = runTest {
        h.index.put(
            entry("j1", "2026-07-01T08:00:00.000Z"),
            entry("j2", "2026-07-20T08:00:00.000Z"),
            entry("j3", "2026-06-05T08:00:00.000Z"),
        )
        val (vm, screen) = open()
        assertEquals(listOf("2026-07-20", "2026-07-01", "2026-06-05"), screen.value.keys)
        vm.onAction(GalleryAction.Group(GalleryGroup.MONTH))
        assertEquals(listOf("m:2026-07", "m:2026-06"), screen.value.keys)
        assertEquals(listOf("j2", "j1", "j3"), screen.value.ids)
    }

    @Test
    fun groupsByYearTagOrNotAtAll() = runTest {
        h.index.put(
            entry("a", "2026-11-02T10:00:00.000Z", tags = listOf("beach")),
            entry("b", "2026-10-02T08:00:00.000Z", tags = listOf("beach", "city")),
            entry("c", "2025-11-01T09:00:00.000Z"),
        )
        val (vm, screen) = open()
        vm.onAction(GalleryAction.Group(GalleryGroup.YEAR))
        assertEquals(listOf("y:2026", "y:2025"), screen.value.keys)
        vm.onAction(GalleryAction.Group(GalleryGroup.TAG))
        assertEquals(listOf("t:beach", "t:city", "n:"), screen.value.keys)
        assertEquals(listOf(listOf("a", "b"), listOf("b"), listOf("c")), screen.value.grid.sections.map { s -> s.tiles.map { it.entryId } })
        assertEquals(listOf(0, 1, 2, 3), screen.value.positions)
        vm.onAction(GalleryAction.Group(GalleryGroup.NONE))
        assertEquals(listOf(""), screen.value.keys)
        assertEquals(listOf("a", "b", "c"), screen.value.ids)
    }

    @Test
    fun theGroupChoiceWritesBothGroupFieldsLikeV031() = runTest {
        val (vm, _) = openWithThree()
        vm.onAction(GalleryAction.Group(GalleryGroup.MONTH))
        assertEquals(defaults.copy(galleryGroupBy = GalleryGroup.MONTH, galleryGroupByDay = true), h.settings.value)
        vm.onAction(GalleryAction.Group(GalleryGroup.NONE))
        assertEquals(defaults.copy(galleryGroupBy = GalleryGroup.NONE, galleryGroupByDay = false), h.settings.value)
        vm.onAction(GalleryAction.Group(GalleryGroup.DAY))
        assertEquals(defaults, h.settings.value)
    }

    @Test
    fun turningGroupingOffInSettingsShowsOneUngroupedListWhateverTheGroup() = runTest {
        h.settings.value = defaults.copy(galleryGroupByDay = false, galleryGroupBy = GalleryGroup.MONTH)
        val (vm, screen) = openWithThree()
        assertEquals(listOf(""), screen.value.keys)
        vm.onAction(GalleryAction.OpenSheet)
        assertEquals(GalleryGroup.NONE, screen.value.settingsSheet.group)
    }

    @Test
    fun choosingTheActiveSortFlipsItsDirectionAndAnotherSortKeepsTheDirection() = runTest {
        val (vm, _) = openWithThree()
        vm.onAction(GalleryAction.Sort(SortKey.TAKEN_AT))
        assertEquals(defaults.copy(gallerySort = GallerySort(SortKey.TAKEN_AT, descending = false)), h.settings.value)
        vm.onAction(GalleryAction.Sort(SortKey.NOTE_LENGTH))
        assertEquals(GallerySort(SortKey.NOTE_LENGTH, descending = false), h.settings.value.gallerySort)
        vm.onAction(GalleryAction.Sort(SortKey.NOTE_LENGTH))
        assertEquals(GallerySort(SortKey.NOTE_LENGTH, descending = true), h.settings.value.gallerySort)
        vm.onAction(GalleryAction.Sort(SortKey.MEDIA_COUNT))
        assertEquals(defaults.copy(gallerySort = GallerySort(SortKey.MEDIA_COUNT, descending = true)), h.settings.value)
    }

    @Test
    fun oldestFirstReversesTheDaysAndTheirEntries() = runTest {
        h.index.put(walk, lake, city, entry("e4", "2026-11-17T08:00:00.000Z"))
        val (vm, screen) = open()
        vm.onAction(GalleryAction.Sort(SortKey.TAKEN_AT))
        assertEquals(listOf("2026-11-16", "2026-11-17", "2026-11-18"), screen.value.keys)
        assertEquals(listOf("e3", "e4", "e2", "e1"), screen.value.ids)
    }

    @Test
    fun anotherSortThanDateShowsOneUngroupedListEvenWithDayGrouping() = runTest {
        h.index.put(
            entry("a", "2026-11-02T10:00:00.000Z", mediaCount = 3),
            entry("b", "2026-11-02T08:00:00.000Z", mediaCount = 1),
            entry("c", "2026-11-01T09:00:00.000Z", mediaCount = 2),
        )
        val (vm, screen) = open()
        vm.onAction(GalleryAction.Sort(SortKey.MEDIA_COUNT))
        assertEquals(listOf(""), screen.value.keys)
        assertEquals(listOf("a", "c", "b"), screen.value.ids)
    }

    @Test
    fun randomSortShufflesWithTheSeedAndChoosingRandomAgainReshuffles() = runTest {
        val many = (1..8).map { entry("r$it", "2026-11-${10 + it}T09:00:00.000Z") }
        h.index.put(*many.toTypedArray())
        h.settings.value = defaults.copy(gallerySort = GallerySort(SortKey.RANDOM, descending = true))
        val (vm, screen) = open()
        val newestFirst = many.reversed().map { it.id }
        val first = sortEntryIds(newestFirst, h.index.gallery::get, IndexSort(IndexSortKey.RANDOM, true), 1.0)
        val second = sortEntryIds(newestFirst, h.index.gallery::get, IndexSort(IndexSortKey.RANDOM, false), 2.0)
        assertNotEquals(first, second)
        assertEquals(first, screen.value.ids)
        vm.onAction(GalleryAction.Sort(SortKey.RANDOM))
        assertEquals(GallerySort(SortKey.RANDOM, descending = false), h.settings.value.gallerySort)
        assertEquals(second, screen.value.ids)
    }

    @Test
    fun displayChangesArePersistedAsDeviceSettings() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.OpenSheet)
        vm.onAction(GalleryAction.DisplayMode(GalleryDisplayMode.LIST))
        vm.onAction(GalleryAction.Columns(landscape = false, columns = 4))
        vm.onAction(GalleryAction.Columns(landscape = true, columns = 6))
        vm.onAction(GalleryAction.Badge(BadgeKey.PENDING, on = false))
        vm.onAction(GalleryAction.Badge(BadgeKey.PHOTO_COUNT, on = false))
        vm.onAction(GalleryAction.ShowTagTabs(false))
        vm.onAction(GalleryAction.ShowCounts(true))
        val expected = defaults.copy(
            galleryDisplayMode = GalleryDisplayMode.LIST,
            galleryColumnsPortrait = 4,
            galleryColumnsLandscape = 6,
            galleryBadges = defaults.galleryBadges.copy(pending = false, photoCount = false),
            galleryShowTagTabs = false,
            galleryShowCounts = true,
        )
        assertEquals(expected, h.settings.value)
        assertEquals(expected, screen.value.settingsSheet.settings)
        assertTrue(screen.value.showCounts)
    }

    @Test
    fun theFourOtherBadgesTurnOffOneByOne() = runTest {
        val (vm, _) = openWithThree()
        vm.onAction(GalleryAction.Badge(BadgeKey.FAVORITE, on = false))
        vm.onAction(GalleryAction.Badge(BadgeKey.VIDEO, on = false))
        vm.onAction(GalleryAction.Badge(BadgeKey.NOTE, on = false))
        assertEquals(defaults.galleryBadges.copy(favorite = false, video = false, note = false), h.settings.value.galleryBadges)
        vm.onAction(GalleryAction.Badge(BadgeKey.VIDEO, on = true))
        assertEquals(defaults.galleryBadges.copy(favorite = false, note = false), h.settings.value.galleryBadges)
    }

    @Test
    fun triStateRowsCycleOffIncludeExcludeAndArePersisted() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.OpenSheet)
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        assertEquals(defaults.copy(galleryFilters = defaults.galleryFilters.copy(video = TriState.INCLUDE)), h.settings.value)
        assertEquals(TriState.INCLUDE, screen.value.settingsSheet.settings.galleryFilters.video)
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        assertEquals(TriState.EXCLUDE, h.settings.value.galleryFilters.video)
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        assertEquals(defaults, h.settings.value)
        for (key in AttributeKey.entries) vm.onAction(GalleryAction.CycleFilter(key))
        assertEquals(
            defaults.galleryFilters.copy(
                video = TriState.INCLUDE,
                note = TriState.INCLUDE,
                multiple = TriState.INCLUDE,
                shared = TriState.INCLUDE,
                pending = TriState.INCLUDE,
                favorite = TriState.INCLUDE,
            ),
            h.settings.value.galleryFilters,
        )
    }

    @Test
    fun attributeFiltersNarrowTheGalleryAndCountAsActiveWithoutChips() = runTest {
        h.index.put(
            entry("photo", "2026-10-01T08:00:00.000Z", note = "photo"),
            entry("clip", "2026-10-02T08:00:00.000Z", note = "clip", hasVideo = true),
        )
        val (vm, screen) = open()
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        assertEquals(listOf("clip"), screen.value.ids)
        assertTrue(screen.value.filterActive)
        assertEquals(emptyList<FilterChip>(), screen.value.chips)
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        assertEquals(listOf("photo"), screen.value.ids)
    }

    @Test
    fun theFavoriteFilterIncludesOrExcludesFavorites() = runTest {
        h.index.put(
            entry("keep", "2026-08-01T08:00:00.000Z", favorite = true),
            entry("other", "2026-08-02T08:00:00.000Z"),
        )
        val (vm, screen) = open()
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.FAVORITE))
        assertEquals(listOf("keep"), screen.value.ids)
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.FAVORITE))
        assertEquals(listOf("other"), screen.value.ids)
    }

    @Test
    fun hideSharedJournalsExcludesSharedEntriesWithoutMakingTheFilterActive() = runTest {
        h.index.put(walk, entry("shared", "2026-11-17T09:00:00.000Z", isOwn = false, collectionIds = listOf("other")))
        h.settings.value = defaults.copy(hideShared = true)
        val (_, screen) = open()
        assertEquals(listOf("e1"), screen.value.ids)
        assertFalse(screen.value.filterActive)
    }

    @Test
    fun deviceSettingsSurviveANewScreenWhileTheInMemoryFilterEndsWithTheLock() = runTest {
        h.index.put(
            entry("photo", "2026-10-01T08:00:00.000Z", note = "photo"),
            entry("clip", "2026-10-02T08:00:00.000Z", note = "clip", hasVideo = true),
        )
        val (first, _) = open()
        first.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        first.onAction(GalleryAction.DisplayMode(GalleryDisplayMode.LIST))
        first.onAction(GalleryAction.ToggleSearch)
        first.onAction(GalleryAction.SearchText("cl"))
        val (_, again) = open()
        assertTrue(again.value.searching)
        assertEquals("cl", again.value.searchText)
        assertEquals(listOf("clip"), again.value.ids)
        h.filter.reset()
        val (_, afterLock) = open()
        assertFalse(afterLock.value.searching)
        assertEquals("", afterLock.value.searchText)
        assertEquals(GalleryDisplayMode.LIST, afterLock.value.grid.display.mode)
        assertEquals(listOf("clip"), afterLock.value.ids)
    }

    @Test
    fun tagsChosenInTheSheetNarrowToEntriesWithEveryTag() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.OpenSheet)
        assertEquals(FilterDraft(), screen.value.settingsSheet.draft)
        assertEquals(listOf("a", "b", "c"), screen.value.settingsSheet.tagChoices)
        vm.onAction(GalleryAction.ChooseTags)
        assertTrue(screen.value.settingsSheet.draft.choosingTags)
        vm.onAction(GalleryAction.DraftTag("a"))
        vm.onAction(GalleryAction.DraftTag("b"))
        vm.onAction(GalleryAction.DraftTag("c"))
        vm.onAction(GalleryAction.DraftTag("c"))
        vm.onAction(GalleryAction.TagsDone)
        assertEquals(FilterDraft(tags = listOf("a", "b")), screen.value.settingsSheet.draft)
        assertEquals(listOf("e1", "e2", "e3"), screen.value.ids)
        vm.onAction(GalleryAction.ApplyFilter)
        assertNull(screen.value.sheet)
        assertEquals(listOf("e2"), screen.value.ids)
        assertEquals(listOf("a", "b"), h.filter.filter.value.tags)
        assertEquals(listOf(FilterChip.Tag("a"), FilterChip.Tag("b")), screen.value.chips)
        assertTrue(screen.value.filterActive)
    }

    @Test
    fun searchFindsNoteWordsByPrefixAndClosingTheSearchClearsTheText() = runTest {
        val (vm, screen) = openWithThree()
        assertFalse(screen.value.searching)
        vm.onAction(GalleryAction.ToggleSearch)
        assertTrue(screen.value.searching)
        vm.onAction(GalleryAction.SearchText("lak"))
        assertEquals(listOf("e2"), screen.value.ids)
        assertEquals("lak", screen.value.searchText)
        assertEquals(listOf(FilterChip.Text("lak")), screen.value.chips)
        vm.onAction(GalleryAction.ToggleSearch)
        assertFalse(screen.value.searching)
        assertEquals("", h.filter.filter.value.text)
        assertEquals(listOf("e1", "e2", "e3"), screen.value.ids)
    }

    @Test
    fun aBackwardsDateRangeIsReportedAndNotApplied() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.OpenSheet)
        vm.onAction(GalleryAction.DraftFrom("2026-11-18"))
        vm.onAction(GalleryAction.DraftTo("2026-11-17"))
        vm.onAction(GalleryAction.ApplyFilter)
        assertEquals(DateRangeProblem.START_AFTER_END, screen.value.settingsSheet.draft.problem)
        assertEquals(GalleryFilters.EMPTY, h.filter.filter.value)
        vm.onAction(GalleryAction.DraftFrom("2026-02-30"))
        vm.onAction(GalleryAction.ApplyFilter)
        assertEquals(DateRangeProblem.INVALID_DATE, screen.value.settingsSheet.draft.problem)
        vm.onAction(GalleryAction.DraftFrom(" 2026-11-17 "))
        vm.onAction(GalleryAction.DraftTo("2026-11-18"))
        vm.onAction(GalleryAction.ApplyFilter)
        assertNull(screen.value.sheet)
        assertEquals(listOf("e1", "e2"), screen.value.ids)
        assertEquals("2026-11-17", h.filter.filter.value.fromDate)
        assertEquals("2026-11-18", h.filter.filter.value.toDate)
        assertEquals(listOf(FilterChip.Dates("2026-11-17", "2026-11-18")), screen.value.chips)
    }

    @Test
    fun blankDatesApplyAsNoBound() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.OpenSheet)
        vm.onAction(GalleryAction.DraftFrom("2026-11-17"))
        vm.onAction(GalleryAction.DraftTo("  "))
        vm.onAction(GalleryAction.ApplyFilter)
        assertEquals("2026-11-17", h.filter.filter.value.fromDate)
        assertNull(h.filter.filter.value.toDate)
        assertEquals(listOf(FilterChip.Dates("2026-11-17", null)), screen.value.chips)
    }

    @Test
    fun theDraftStartsFromTheAppliedFilterAndResetOnlyClearsTheDraft() = runTest {
        val (vm, screen) = openWithThree()
        h.filter.set(GalleryFilters.EMPTY.copy(tags = listOf("a"), fromDate = "2026-11-01", text = "lake"))
        vm.onAction(GalleryAction.OpenSheet)
        assertEquals(FilterDraft(tags = listOf("a"), from = "2026-11-01", to = ""), screen.value.settingsSheet.draft)
        vm.onAction(GalleryAction.ResetFilter)
        assertEquals(FilterDraft(), screen.value.settingsSheet.draft)
        assertEquals(listOf("a"), h.filter.filter.value.tags)
        vm.onAction(GalleryAction.ApplyFilter)
        assertEquals(GalleryFilters.EMPTY.copy(text = "lake"), h.filter.filter.value)
    }

    @Test
    fun dismissingTheSheetDropsTheDraft() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.OpenSheet)
        vm.onAction(GalleryAction.DraftTag("c"))
        vm.onAction(GalleryAction.DismissSheet)
        assertNull(screen.value.sheet)
        assertEquals(GalleryFilters.EMPTY, h.filter.filter.value)
        vm.onAction(GalleryAction.OpenSheet)
        assertEquals(FilterDraft(), screen.value.settingsSheet.draft)
    }

    @Test
    fun eachChipRemovesItsOwnFilter() = runTest {
        val (vm, screen) = openWithThree()
        h.filter.set(GalleryFilters.EMPTY.copy(tags = listOf("a", "b"), fromDate = "2026-11-01", toDate = "2026-11-30", text = " lake "))
        assertEquals(
            listOf(FilterChip.Tag("a"), FilterChip.Tag("b"), FilterChip.Dates("2026-11-01", "2026-11-30"), FilterChip.Text("lake")),
            screen.value.chips,
        )
        vm.onAction(GalleryAction.RemoveChip(FilterChip.Tag("a")))
        assertEquals(listOf("b"), h.filter.filter.value.tags)
        vm.onAction(GalleryAction.RemoveChip(FilterChip.Dates("2026-11-01", "2026-11-30")))
        assertNull(h.filter.filter.value.fromDate)
        assertNull(h.filter.filter.value.toDate)
        vm.onAction(GalleryAction.RemoveChip(FilterChip.Text("lake")))
        assertEquals("", h.filter.filter.value.text)
        assertEquals(listOf(FilterChip.Tag("b")), screen.value.chips)
    }

    @Test
    fun clearAllEmptiesTheFilterAndClosesTheSearch() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.ToggleSearch)
        vm.onAction(GalleryAction.SearchText("lake"))
        h.filter.set(h.filter.filter.value.copy(tags = listOf("a")))
        vm.onAction(GalleryAction.ClearAll)
        assertEquals(GalleryFilters.EMPTY, h.filter.filter.value)
        assertFalse(screen.value.searching)
        assertEquals(emptyList<FilterChip>(), screen.value.chips)
        assertEquals(listOf("e1", "e2", "e3"), screen.value.ids)
    }

    @Test
    fun anEmptyJournalShowsTheEmptyState() = runTest {
        val (_, screen) = open()
        assertEquals(GalleryEmpty.EMPTY, screen.value.empty)
        h.index.put(walk)
        assertNull(screen.value.empty)
    }

    @Test
    fun aFilterWithoutMatchesShowsNoMatches() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.ToggleSearch)
        vm.onAction(GalleryAction.SearchText("nothing"))
        assertEquals(GalleryEmpty.NO_MATCHES, screen.value.empty)
        vm.onAction(GalleryAction.ClearFilters)
        assertNull(screen.value.empty)
        assertFalse(screen.value.searching)
        assertEquals(GalleryFilters.EMPTY, h.filter.filter.value)
    }

    @Test
    fun clearFiltersResetsOnlyTheInMemoryFilterLikeV031() = runTest {
        h.index.put(walk)
        h.settings.value = defaults.copy(galleryFilters = defaults.galleryFilters.copy(video = TriState.INCLUDE))
        val (vm, screen) = open()
        assertEquals(GalleryEmpty.NO_MATCHES, screen.value.empty)
        vm.onAction(GalleryAction.ClearFilters)
        assertEquals(TriState.INCLUDE, h.settings.value.galleryFilters.video)
        assertEquals(GalleryEmpty.NO_MATCHES, screen.value.empty)
    }

    @Test
    fun tagTabsListAllFavoritesAndTheTagsWithCountsAndAllSelected() = runTest {
        h.index.put(
            entry("t1", "2026-09-01T08:00:00.000Z", tags = listOf("beach"), favorite = true),
            entry("t2", "2026-09-02T08:00:00.000Z", tags = listOf("city")),
            entry("t3", "2026-09-03T08:00:00.000Z", tags = listOf("city")),
        )
        val (_, screen) = open()
        assertEquals(
            listOf(TagTab.All(3, true), TagTab.Favorites(1, false), TagTab.Tag(0, "beach", 1, false), TagTab.Tag(1, "city", 2, false)),
            screen.value.tagTabs,
        )
        h.settings.value = defaults.copy(tagOrder = listOf("city", "zoo"))
        assertEquals(
            listOf(TagTab.All(3, true), TagTab.Favorites(1, false), TagTab.Tag(0, "city", 2, false), TagTab.Tag(1, "zoo", 0, false), TagTab.Tag(2, "beach", 1, false)),
            screen.value.tagTabs,
        )
    }

    @Test
    fun aTagTabShowsOnlyThatTagAndAllShowsEverything() = runTest {
        h.index.put(
            entry("t1", "2026-09-01T08:00:00.000Z", tags = listOf("beach")),
            entry("t2", "2026-09-02T08:00:00.000Z", tags = listOf("city")),
            entry("t3", "2026-09-03T08:00:00.000Z", tags = listOf("city")),
        )
        val (vm, screen) = open()
        vm.onAction(GalleryAction.SelectTab(TagTab.Tag(1, "city", 2, false)))
        assertEquals(listOf("t3", "t2"), screen.value.ids)
        assertEquals(listOf("city"), h.filter.filter.value.tags)
        assertEquals(listOf(TagTab.All(3, false), TagTab.Tag(0, "beach", 1, false), TagTab.Tag(1, "city", 2, true)), screen.value.tagTabs)
        vm.onAction(GalleryAction.SelectTab(TagTab.All(3, false)))
        assertEquals(3, screen.value.ids.size)
        assertEquals(emptyList<String>(), h.filter.filter.value.tags)
        assertEquals(0, h.settings.updates)
    }

    @Test
    fun theFavoritesTabDrivesThePersistedFavoriteFilter() = runTest {
        h.index.put(
            entry("keep", "2026-08-01T08:00:00.000Z", tags = listOf("a"), favorite = true),
            entry("other", "2026-08-02T08:00:00.000Z", tags = listOf("a")),
        )
        h.filter.set(GalleryFilters.EMPTY.copy(tags = listOf("a")))
        val (vm, screen) = open()
        vm.onAction(GalleryAction.SelectTab(TagTab.Favorites(1, false)))
        assertEquals(defaults.copy(galleryFilters = defaults.galleryFilters.copy(favorite = TriState.INCLUDE)), h.settings.value)
        assertEquals(emptyList<String>(), h.filter.filter.value.tags)
        assertEquals(listOf("keep"), screen.value.ids)
        assertEquals(listOf(TagTab.All(2, false), TagTab.Favorites(1, true), TagTab.Tag(0, "a", 2, false)), screen.value.tagTabs)
        vm.onAction(GalleryAction.SelectTab(TagTab.All(2, false)))
        assertEquals(defaults, h.settings.value)
        assertEquals(listOf("other", "keep"), screen.value.ids)
        assertEquals(2, h.settings.updates)
    }

    @Test
    fun noTabIsSelectedWhileSeveralTagsFilter() = runTest {
        h.index.put(walk, lake, city)
        h.filter.set(GalleryFilters.EMPTY.copy(tags = listOf("a", "b")))
        val (_, screen) = open()
        assertTrue(screen.value.tagTabs.none { it.selected })
    }

    @Test
    fun tabCountsIgnoreTagAndAttributeFiltersButFollowTheSearchAndDates() = runTest {
        h.index.put(walk, lake, city.copy(hasVideo = true))
        h.settings.value = defaults.copy(galleryFilters = defaults.galleryFilters.copy(video = TriState.INCLUDE))
        h.filter.set(GalleryFilters.EMPTY.copy(tags = listOf("c")))
        val (vm, screen) = open()
        assertEquals(TagTab.All(3, false), screen.value.tagTabs.first())
        vm.onAction(GalleryAction.ToggleSearch)
        vm.onAction(GalleryAction.SearchText("lake"))
        assertEquals(1, screen.value.tagTabs.first().count)
    }

    @Test
    fun tagTabsHideWithTheSettingOrWithoutTagsAndFavorites() = runTest {
        h.index.put(entry("plain", "2026-11-18T09:00:00.000Z"))
        val (_, screen) = open()
        assertEquals(emptyList<TagTab>(), screen.value.tagTabs)
        h.index.put(entry("loved", "2026-11-17T09:00:00.000Z", favorite = true))
        assertEquals(listOf(TagTab.All(2, true), TagTab.Favorites(1, false)), screen.value.tagTabs)
        h.settings.value = defaults.copy(galleryShowTagTabs = false)
        assertEquals(emptyList<TagTab>(), screen.value.tagTabs)
    }

    @Test
    fun theItemCountIsTheNumberOfShownEntriesAndShowsWithTheSetting() = runTest {
        val (vm, screen) = openWithThree()
        assertFalse(screen.value.showCounts)
        assertEquals(3, screen.value.grid.order.size)
        vm.onAction(GalleryAction.ShowCounts(true))
        assertTrue(screen.value.showCounts)
        vm.onAction(GalleryAction.SelectTab(TagTab.Tag(0, "a", 2, false)))
        assertEquals(listOf("e1", "e2"), screen.value.grid.order)
    }

    @Test
    fun onThisDayShowsTodaysDateFromEarlierYearsGroupedByYearsAgo() = runTest {
        h.index.put(
            entry("y1", "2025-11-21T09:00:00.000Z"),
            entry("y2a", "2024-11-21T08:00:00.000Z"),
            entry("y2b", "2024-11-21T18:00:00.000Z", unreadable = true, coverMediaId = null),
            entry("now", "2026-11-21T07:00:00.000Z"),
            entry("near", "2025-11-20T09:00:00.000Z"),
            entry("gone", "2023-11-21T09:00:00.000Z", trashed = true),
            entry("lateEvening", "2022-11-20T23:30:00.000Z", utcOffsetMinutes = 60),
        )
        val (_, screen) = open()
        assertEquals(
            listOf(
                OnThisDayYear(1, listOf(OnThisDayEntry("y1", "media-y1", false))),
                OnThisDayYear(2, listOf(OnThisDayEntry("y2b", null, true), OnThisDayEntry("y2a", "media-y2a", false))),
                OnThisDayYear(4, listOf(OnThisDayEntry("lateEvening", "media-lateEvening", false))),
            ),
            screen.value.onThisDay,
        )
    }

    @Test
    fun onThisDayHidesWhileAnyFilterIsActive() = runTest {
        h.index.put(walk, entry("y1", "2025-11-21T09:00:00.000Z", hasVideo = true))
        val (vm, screen) = open()
        assertEquals(1, screen.value.onThisDay.size)
        vm.onAction(GalleryAction.ToggleSearch)
        vm.onAction(GalleryAction.SearchText("walk"))
        assertEquals(emptyList<OnThisDayYear>(), screen.value.onThisDay)
        vm.onAction(GalleryAction.ClearAll)
        assertEquals(1, screen.value.onThisDay.size)
        vm.onAction(GalleryAction.CycleFilter(AttributeKey.VIDEO))
        assertEquals(emptyList<OnThisDayYear>(), screen.value.onThisDay)
    }

    @Test
    fun onThisDayKeepsTenEntriesPerYear() = runTest {
        h.index.put(*(10..21).map { entry("d$it", "2025-11-21T$it:00:00.000Z") }.toTypedArray())
        val (_, screen) = open()
        val year = screen.value.onThisDay.single()
        assertEquals(1, year.yearsAgo)
        assertEquals(ON_THIS_DAY_TILE_LIMIT, year.entries.size)
        assertEquals((21 downTo 12).map { "d$it" }, year.entries.map { it.entryId })
    }

    @Test
    fun onThisDayIgnoresTheStoredSettingLikeV031() = runTest {
        h.index.put(entry("y1", "2025-11-21T09:00:00.000Z"))
        h.settings.value = defaults.copy(onThisDay = false)
        val (_, screen) = open()
        assertEquals(1, screen.value.onThisDay.size)
    }

    @Test
    fun anOnThisDayEntryOpensItsPage() = runTest {
        h.index.put(entry("y1", "2025-11-21T09:00:00.000Z"))
        val (vm, screen) = open()
        vm.onAction(GalleryAction.OpenEntry("y1"))
        assertEquals(listOf<GalleryEvent>(GalleryEvent.Open(Route.Entry("y1"))), screen.events)
    }

    @Test
    fun tappingATileOpensTheEntry() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileClick("e2"))
        assertEquals(listOf<GalleryEvent>(GalleryEvent.Open(Route.Entry("e2"))), screen.events)
        assertFalse(screen.value.grid.selection.active)
    }

    @Test
    fun aLongPressStartsSelectionWithAHapticTick() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e2"))
        assertEquals(GallerySelection(active = true, ids = listOf("e2")), screen.value.grid.selection)
        assertEquals(listOf(HapticKind.SELECT), h.haptics)
        vm.onAction(GalleryAction.TileLongClick("e3"))
        assertEquals(GallerySelection(active = true, ids = listOf("e2")), screen.value.grid.selection)
        assertEquals(listOf(HapticKind.SELECT), h.haptics)
        assertEquals(emptyList<GalleryEvent>(), screen.events)
    }

    @Test
    fun tapsWhileSelectingToggleEntriesAndSelectionStaysOnWithNothingSelected() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e1"))
        vm.onAction(GalleryAction.TileClick("e3"))
        assertEquals(listOf("e1", "e3"), screen.value.grid.selection.ids)
        vm.onAction(GalleryAction.TileClick("e1"))
        assertEquals(listOf("e3"), screen.value.grid.selection.ids)
        vm.onAction(GalleryAction.TileClick("e3"))
        assertEquals(GallerySelection(active = true, ids = emptyList()), screen.value.grid.selection)
        assertEquals(emptyList<GalleryEvent>(), screen.events)
    }

    @Test
    fun selectAllTakesEveryShownEntryInDisplayOrderAndInvertSelectsTheOthers() = runTest {
        h.index.put(walk, lake, city, entry("e4", "2026-11-15T09:00:00.000Z"))
        val (vm, screen) = open()
        vm.onAction(GalleryAction.TileLongClick("e3"))
        vm.onAction(GalleryAction.TileClick("e1"))
        vm.onAction(GalleryAction.InvertSelection)
        assertEquals(GallerySelection(active = true, ids = listOf("e2", "e4")), screen.value.grid.selection)
        vm.onAction(GalleryAction.SelectAll)
        assertEquals(listOf("e1", "e2", "e3", "e4"), screen.value.grid.selection.ids)
        vm.onAction(GalleryAction.InvertSelection)
        assertEquals(GallerySelection(active = true, ids = emptyList()), screen.value.grid.selection)
        vm.onAction(GalleryAction.ClearSelection)
        assertEquals(GallerySelection(), screen.value.grid.selection)
    }

    @Test
    fun selectAllWorksWithoutAStartedSelectionAndOnlyTakesTheFilteredEntries() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.SelectTab(TagTab.Tag(0, "a", 2, false)))
        vm.onAction(GalleryAction.SelectAll)
        assertEquals(GallerySelection(active = true, ids = listOf("e1", "e2")), screen.value.grid.selection)
    }

    @Test
    fun leavingTheScreenClearsTheSelectionClosesTheSheetAndShowsTheBars() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.Scrolled(100f))
        vm.onAction(GalleryAction.Scrolled(300f))
        vm.onAction(GalleryAction.TileLongClick("e1"))
        vm.onAction(GalleryAction.OpenActions)
        assertTrue(screen.value.barsHidden)
        vm.onAction(GalleryAction.ScreenLeft)
        assertEquals(GallerySelection(), screen.value.grid.selection)
        assertNull(screen.value.sheet)
        assertFalse(screen.value.barsHidden)
    }

    @Test
    fun scrollingDownHidesTheBarsAndScrollingUpOrNearTheTopShowsThem() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.Scrolled(100f))
        vm.onAction(GalleryAction.Scrolled(200f))
        assertTrue(screen.value.barsHidden)
        vm.onAction(GalleryAction.Scrolled(190f))
        assertTrue(screen.value.barsHidden)
        vm.onAction(GalleryAction.Scrolled(150f))
        assertFalse(screen.value.barsHidden)
        vm.onAction(GalleryAction.Scrolled(400f))
        assertTrue(screen.value.barsHidden)
        vm.onAction(GalleryAction.Scrolled(10f))
        assertFalse(screen.value.barsHidden)
    }

    @Test
    fun theBarsStayWhenHidingOnScrollIsOff() = runTest {
        h.settings.value = defaults.copy(hideBarsOnScroll = false)
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.Scrolled(100f))
        vm.onAction(GalleryAction.Scrolled(500f))
        assertFalse(screen.value.barsHidden)
    }

    @Test
    fun actionsOpenTheJournalBulkSheetForTheSelection() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e3"))
        vm.onAction(GalleryAction.TileClick("e1"))
        vm.onAction(GalleryAction.OpenActions)
        assertEquals(BulkSheet(mode = BulkMode.JOURNAL, panel = BulkPanel.MENU, count = 2, tagChoices = listOf("a", "b", "c")), screen.value.bulk)
    }

    @Test
    fun addTagPutsThePickedTagOnEverySelectedEntryReportsAndClearsTheSelectionWhenTheSheetCloses() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e3"))
        vm.onAction(GalleryAction.TileClick("e1"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkAddTag)
        assertEquals(BulkPanel.ADD_TAG, screen.value.bulk.panel)
        vm.onAction(GalleryAction.BulkConfirmTag)
        assertEquals(emptyList<BulkCall>(), h.entries.calls)
        vm.onAction(GalleryAction.BulkPickTag("b"))
        assertEquals("b", screen.value.bulk.pickedTag)
        vm.onAction(GalleryAction.BulkConfirmTag)
        assertEquals(listOf(BulkCall("addTagToMany", listOf("e3", "e1"), "b")), h.entries.calls)
        assertEquals(GallerySnackbar.BulkDone(2, 0), screen.value.snackbar)
        assertEquals(BulkSheet(mode = BulkMode.JOURNAL, panel = BulkPanel.MENU, count = 2, tagChoices = listOf("a", "b", "c")), screen.value.bulk)
        assertTrue(screen.value.grid.selection.active)
        vm.onAction(GalleryAction.DismissSheet)
        assertNull(screen.value.sheet)
        assertEquals(GallerySelection(), screen.value.grid.selection)
    }

    @Test
    fun removeTagFavoriteAndUnfavoriteRunOnTheSelection() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e2"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkRemoveTag)
        assertEquals(BulkPanel.REMOVE_TAG, screen.value.bulk.panel)
        vm.onAction(GalleryAction.BulkPickTag("a"))
        vm.onAction(GalleryAction.BulkConfirmTag)
        vm.onAction(GalleryAction.BulkFavorite(true))
        vm.onAction(GalleryAction.BulkFavorite(false))
        assertEquals(
            listOf(
                BulkCall("removeTagFromMany", listOf("e2"), "a"),
                BulkCall("setFavorite", listOf("e2"), true),
                BulkCall("setFavorite", listOf("e2"), false),
            ),
            h.entries.calls,
        )
        assertEquals(listOf("b"), screen.value.tile("e2").tags)
    }

    @Test
    fun cancelInATagPanelReturnsToTheMenuWithoutRunning() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e2"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkAddTag)
        vm.onAction(GalleryAction.BulkPickTag("c"))
        vm.onAction(GalleryAction.BulkCancel)
        assertEquals(BulkPanel.MENU, screen.value.bulk.panel)
        assertNull(screen.value.bulk.pickedTag)
        assertEquals(emptyList<BulkCall>(), h.entries.calls)
        vm.onAction(GalleryAction.DismissSheet)
        assertEquals(GallerySelection(active = true, ids = listOf("e2")), screen.value.grid.selection)
    }

    @Test
    fun moveToTrashTakesTheSelectionOutOfTheGallery() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e1"))
        vm.onAction(GalleryAction.TileClick("e2"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkTrash)
        assertEquals(listOf(BulkCall("trash", listOf("e1", "e2"))), h.entries.calls)
        assertEquals(listOf("e3"), screen.value.ids)
        assertEquals(GallerySnackbar.BulkDone(2, 0), screen.value.snackbar)
    }

    @Test
    fun skippedEntriesAreReported() = runTest {
        val (vm, screen) = openWithThree()
        h.entries.result = BulkResult(1, 2)
        vm.onAction(GalleryAction.SelectAll)
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkFavorite(true))
        assertEquals(GallerySnackbar.BulkDone(1, 2), screen.value.snackbar)
    }

    @Test
    fun aBulkActionShowsBusyUntilItFinishes() = runTest {
        val (vm, screen) = openWithThree()
        val gate = CompletableDeferred<Unit>()
        h.entries.gate = gate
        vm.onAction(GalleryAction.SelectAll)
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkFavorite(true))
        assertTrue(screen.value.bulk.busy)
        vm.onAction(GalleryAction.BulkFavorite(true))
        assertEquals(1, h.entries.calls.size)
        gate.complete(Unit)
        assertFalse(screen.value.bulk.busy)
    }

    @Test
    fun aFailedBulkActionShowsTheGenericErrorAndKeepsTheSelection() = runTest {
        val (vm, screen) = openWithThree()
        h.entries.failure = IllegalStateException("offline")
        vm.onAction(GalleryAction.TileLongClick("e1"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkTrash)
        assertTrue(screen.value.bulk.failed)
        assertFalse(screen.value.bulk.busy)
        assertNull(screen.value.snackbar)
        h.entries.failure = null
        vm.onAction(GalleryAction.BulkFavorite(true))
        assertFalse(screen.value.bulk.failed)
        h.entries.failure = IllegalStateException("offline")
        vm.onAction(GalleryAction.BulkTrash)
        vm.onAction(GalleryAction.DismissSheet)
        assertEquals(GallerySelection(), screen.value.grid.selection)
    }

    @Test
    fun closingTheSheetWithoutActingKeepsTheSelection() = runTest {
        val (vm, screen) = openWithThree()
        h.entries.failure = IllegalStateException("offline")
        vm.onAction(GalleryAction.TileLongClick("e1"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkTrash)
        vm.onAction(GalleryAction.DismissSheet)
        assertEquals(GallerySelection(active = true, ids = listOf("e1")), screen.value.grid.selection)
    }

    @Test
    fun shareHandsTheSelectionToTheShareSheet() = runTest {
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.TileLongClick("e2"))
        vm.onAction(GalleryAction.TileClick("e3"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkShare)
        assertEquals(listOf<GalleryEvent>(GalleryEvent.Share(listOf("e2", "e3"))), screen.events)
    }

    @Test
    fun aRecentSaveShowsSavedAndAnOlderOneShowsNothing() = runTest {
        val (vm, screen) = openWithThree()
        h.notices.value = GalleryNotice.Saved(NOW_MS - 1_999)
        assertEquals(GallerySnackbar.Saved, screen.value.snackbar)
        assertNull(h.notices.value)
        vm.onAction(GalleryAction.SnackbarDismissed)
        assertNull(screen.value.snackbar)
        h.clock.nowMs = NOW_MS + 10_000
        h.notices.value = GalleryNotice.Saved(NOW_MS + 8_000)
        assertNull(screen.value.snackbar)
        assertNull(h.notices.value)
    }

    @Test
    fun aNoticePostedBeforeTheGalleryOpensIsShownOnce() = runTest {
        h.index.put(walk)
        h.notices.value = GalleryNotice.Gone
        val (vm, screen) = open()
        assertEquals(GallerySnackbar.Gone, screen.value.snackbar)
        assertNull(h.notices.value)
        vm.onAction(GalleryAction.SnackbarDismissed)
        assertNull(screen.value.snackbar)
    }

    @Test
    fun aTrashedEntryShowsMovedToTrashWithAnUndoThatRestoresIt() = runTest {
        h.index.put(walk, lake.copy(trashed = true))
        val (vm, screen) = open()
        h.notices.value = GalleryNotice.Trashed("e2")
        assertEquals(GallerySnackbar.Trashed("e2"), screen.value.snackbar)
        assertEquals(listOf("e1"), screen.value.ids)
        vm.onAction(GalleryAction.UndoTrash)
        assertEquals(listOf(BulkCall("restore", listOf("e2"))), h.entries.calls)
        assertNull(screen.value.snackbar)
        assertEquals(listOf("e1", "e2"), screen.value.ids)
    }

    @Test
    fun aFailingUndoIsSwallowed() = runTest {
        h.index.put(walk, lake.copy(trashed = true))
        h.entries.failure = IllegalStateException("gone")
        val (vm, screen) = open()
        h.notices.value = GalleryNotice.Trashed("e2")
        vm.onAction(GalleryAction.UndoTrash)
        assertNull(screen.value.snackbar)
        assertEquals(listOf("e1"), screen.value.ids)
    }

    @Test
    fun aNewNoticeReplacesTheShownSnackbar() = runTest {
        val (_, screen) = openWithThree()
        h.notices.value = GalleryNotice.Gone
        h.notices.value = GalleryNotice.Trashed("e1")
        assertEquals(GallerySnackbar.Trashed("e1"), screen.value.snackbar)
    }

    @Test
    fun pullToSyncIsAvailableOnlyForServerAccountsWithSyncRunning() = runTest {
        val (_, screen) = openWithThree()
        assertTrue(screen.value.pullToSync)
        h.account.modeState.value = AccountMode.LOCAL
        assertFalse(screen.value.pullToSync)
        h.account.modeState.value = AccountMode.SERVER
        h.settings.value = defaults.copy(syncPaused = true)
        assertFalse(screen.value.pullToSync)
    }

    @Test
    fun pullingTicksSyncsAndSpinsUntilTheSyncSettles() = runTest {
        val (vm, screen) = openWithThree()
        val gate = CompletableDeferred<Unit>()
        h.sync.gate = gate
        vm.onAction(GalleryAction.PullToSync)
        assertEquals(listOf(HapticKind.REFRESH), h.haptics)
        assertTrue(screen.value.refreshing)
        assertEquals(1, h.sync.calls)
        gate.complete(Unit)
        assertFalse(screen.value.refreshing)
    }

    @Test
    fun aFailingSyncStillStopsTheSpinner() = runTest {
        val (vm, screen) = openWithThree()
        h.sync.failure = IllegalStateException("offline")
        vm.onAction(GalleryAction.PullToSync)
        assertEquals(1, h.sync.calls)
        assertFalse(screen.value.refreshing)
    }

    @Test
    fun pullingDoesNothingWhenSyncIsUnavailable() = runTest {
        h.account.modeState.value = AccountMode.LOCAL
        val (vm, screen) = openWithThree()
        vm.onAction(GalleryAction.PullToSync)
        assertEquals(0, h.sync.calls)
        assertEquals(emptyList<HapticKind>(), h.haptics)
        assertFalse(screen.value.refreshing)
    }

    @Test
    fun randomOpensAnEntryPickedAmongTheShownOnes() = runTest {
        val (vm, screen) = openWithThree()
        h.random = 0.5
        vm.onAction(GalleryAction.Random)
        h.random = 0.999
        vm.onAction(GalleryAction.Random)
        h.random = 0.0
        vm.onAction(GalleryAction.Random)
        assertEquals(
            listOf<GalleryEvent>(GalleryEvent.Open(Route.Entry("e2")), GalleryEvent.Open(Route.Entry("e3")), GalleryEvent.Open(Route.Entry("e1"))),
            screen.events,
        )
    }

    @Test
    fun randomDoesNothingWhenNothingIsShown() = runTest {
        val (vm, screen) = open()
        vm.onAction(GalleryAction.Random)
        assertEquals(emptyList<GalleryEvent>(), screen.events)
    }

    @Test
    fun theMenuLocksSignsOutOpensTheTrashAndTheButtonOpensTheCamera() = runTest {
        val (vm, screen) = openWithThree()
        h.account.failure = IllegalStateException("busy")
        vm.onAction(GalleryAction.LockNow)
        vm.onAction(GalleryAction.SignOut)
        vm.onAction(GalleryAction.OpenTrash)
        vm.onAction(GalleryAction.Capture)
        vm.onAction(GalleryAction.EditTags)
        assertEquals(listOf("lockNow", "signOut"), h.account.calls)
        assertEquals(
            listOf<GalleryEvent>(GalleryEvent.Open(Route.Trash), GalleryEvent.Open(Route.Capture()), GalleryEvent.Open(Route.Tags)),
            screen.events,
        )
    }
}
