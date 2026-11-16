package dev.kodakiko.ui.feature.gallery

import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.TriState
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrashAndDayViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = Harness()
    private val defaults = DeviceSettingsDefaults.ANDROID

    private val kept = entry("kept", "2026-11-18T09:00:00.000Z")
    private val old = entry("old", "2026-11-10T09:00:00.000Z", trashed = true, favorite = true)
    private val older = entry("older", "2026-11-09T09:00:00.000Z", trashed = true)
    private val oldest = entry("oldest", "2026-10-01T09:00:00.000Z", trashed = true)

    private fun TestScope.trash(): Pair<TrashViewModel, Observed<TrashState, GalleryEvent>> {
        val vm = TrashViewModel(h.deps)
        return vm to observe(vm.state, vm.events)
    }

    private fun TestScope.day(date: String): Pair<DayViewModel, Observed<DayState, GalleryEvent>> {
        val vm = DayViewModel(h.deps, date)
        return vm to observe(vm.state, vm.events)
    }

    private val GalleryGrid.ids: List<String> get() = sections.flatMap { section -> section.tiles.map { it.entryId } }

    @Test
    fun theTrashShowsOnlyTrashedEntriesGroupedByDayNewestFirst() = runTest {
        h.index.put(kept, old, older, oldest)
        val (_, screen) = trash()
        assertFalse(screen.value.opening)
        assertEquals(listOf("old", "older", "oldest"), screen.value.grid.ids)
        assertEquals(listOf("2026-11-10", "2026-11-09", "2026-10-01"), screen.value.grid.sections.map { it.key })
        assertEquals(TODAY, screen.value.grid.today)
    }

    @Test
    fun theRetentionComesFromTheServerConfigWithTheThirtyDayFallback() = runTest {
        h.config.config = publicConfig(trashDays = 14L)
        val (_, configured) = trash()
        assertEquals(14L, configured.value.retentionDays)
        h.config.config = null
        val (_, fallback) = trash()
        assertEquals(TRASH_DAYS, fallback.value.retentionDays)
    }

    @Test
    fun whileTheIndexBuildsTheTrashIsOpening() = runTest {
        h.index.setStatus(IndexStatus.BUILDING)
        val (_, screen) = trash()
        assertTrue(screen.value.opening)
        h.index.put(old)
        h.index.setStatus(IndexStatus.READY)
        assertFalse(screen.value.opening)
        assertEquals(listOf("old"), screen.value.grid.ids)
    }

    @Test
    fun anEmptyTrashHasNoSections() = runTest {
        h.index.put(kept)
        val (_, screen) = trash()
        assertEquals(emptyList<GallerySection>(), screen.value.grid.sections)
    }

    @Test
    fun theTrashUsesTheGallerySortGroupAndAttributeFiltersLikeV031() = runTest {
        h.index.put(kept, old, older, oldest)
        h.settings.value = defaults.copy(galleryGroupBy = GalleryGroup.MONTH)
        val (_, screen) = trash()
        assertEquals(listOf("m:2026-11", "m:2026-10"), screen.value.grid.sections.map { it.key })
        h.settings.value = defaults.copy(galleryFilters = defaults.galleryFilters.copy(favorite = TriState.INCLUDE))
        assertEquals(listOf("old"), screen.value.grid.ids)
    }

    @Test
    fun aTrashedEntryOpensFromItsTileAndBackLeavesTheTrash() = runTest {
        h.index.put(old)
        val (vm, screen) = trash()
        vm.onAction(GalleryAction.TileClick("old"))
        vm.onAction(GalleryAction.Back)
        assertEquals(listOf(GalleryEvent.Open(Route.Entry("old")), GalleryEvent.Back), screen.events)
    }

    @Test
    fun aLongPressSelectsWithATickAndActionsOpenTheTrashSheet() = runTest {
        h.index.put(old, older)
        val (vm, screen) = trash()
        vm.onAction(GalleryAction.TileLongClick("older"))
        vm.onAction(GalleryAction.TileClick("old"))
        assertEquals(listOf(HapticKind.SELECT), h.haptics)
        assertEquals(GallerySelection(active = true, ids = listOf("older", "old")), screen.value.grid.selection)
        vm.onAction(GalleryAction.OpenActions)
        val bulk = (screen.value.sheet as GallerySheet.Actions).bulk
        assertEquals(BulkMode.TRASH, bulk.mode)
        assertEquals(BulkPanel.MENU, bulk.panel)
        assertEquals(2, bulk.count)
    }

    @Test
    fun actionsNeedASelectedEntryInTheTrash() = runTest {
        h.index.put(old)
        val (vm, screen) = trash()
        vm.onAction(GalleryAction.TileLongClick("old"))
        vm.onAction(GalleryAction.TileClick("old"))
        vm.onAction(GalleryAction.OpenActions)
        assertNull(screen.value.sheet)
    }

    @Test
    fun restorePutsTheSelectionBackIntoTheJournal() = runTest {
        h.index.put(kept, old, older)
        val (vm, screen) = trash()
        vm.onAction(GalleryAction.TileLongClick("old"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkRestore)
        assertEquals(listOf(BulkCall("restore", listOf("old"))), h.entries.calls)
        assertEquals(listOf("older"), screen.value.grid.ids)
        assertEquals(GallerySnackbar.BulkDone(1, 0), screen.value.snackbar)
        vm.onAction(GalleryAction.DismissSheet)
        assertEquals(GallerySelection(), screen.value.grid.selection)
    }

    @Test
    fun deletePermanentlyAsksFirstAndCancelGoesBack() = runTest {
        h.index.put(old, older)
        val (vm, screen) = trash()
        vm.onAction(GalleryAction.SelectAll)
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.BulkDeletePermanently)
        assertEquals(BulkPanel.CONFIRM_DELETE, (screen.value.sheet as GallerySheet.Actions).bulk.panel)
        assertEquals(emptyList<BulkCall>(), h.entries.calls)
        vm.onAction(GalleryAction.BulkCancel)
        assertEquals(BulkPanel.MENU, (screen.value.sheet as GallerySheet.Actions).bulk.panel)
        vm.onAction(GalleryAction.BulkDeletePermanently)
        vm.onAction(GalleryAction.BulkConfirmDelete)
        assertEquals(listOf(BulkCall("deletePermanently", listOf("old", "older"))), h.entries.calls)
        assertEquals(emptyList<GallerySection>(), screen.value.grid.sections)
        assertEquals(GallerySnackbar.BulkDone(2, 0), screen.value.snackbar)
        assertEquals(BulkPanel.MENU, (screen.value.sheet as GallerySheet.Actions).bulk.panel)
    }

    @Test
    fun leavingTheTrashClearsTheSelection() = runTest {
        h.index.put(old)
        val (vm, screen) = trash()
        vm.onAction(GalleryAction.TileLongClick("old"))
        vm.onAction(GalleryAction.OpenActions)
        vm.onAction(GalleryAction.ScreenLeft)
        assertEquals(GallerySelection(), screen.value.grid.selection)
        assertNull(screen.value.sheet)
    }

    @Test
    fun theTrashOffersPullToSyncLikeTheGallery() = runTest {
        h.index.put(old)
        val (vm, screen) = trash()
        assertTrue(screen.value.pullToSync)
        val gate = CompletableDeferred<Unit>()
        h.sync.gate = gate
        vm.onAction(GalleryAction.PullToSync)
        assertTrue(screen.value.refreshing)
        assertEquals(listOf(HapticKind.REFRESH), h.haptics)
        gate.complete(Unit)
        assertFalse(screen.value.refreshing)
        assertEquals(1, h.sync.calls)
        h.settings.value = defaults.copy(syncPaused = true)
        assertFalse(screen.value.pullToSync)
        vm.onAction(GalleryAction.PullToSync)
        assertEquals(1, h.sync.calls)
    }

    @Test
    fun aDayOffersPullToSyncOnlyForServerAccounts() = runTest {
        h.index.put(entry("morning", "2026-11-17T08:00:00.000Z"))
        val (vm, screen) = day("2026-11-17")
        assertTrue(screen.value.pullToSync)
        h.sync.failure = IllegalStateException("offline")
        vm.onAction(GalleryAction.PullToSync)
        assertEquals(1, h.sync.calls)
        assertFalse(screen.value.refreshing)
        h.account.modeState.value = AccountMode.LOCAL
        assertFalse(screen.value.pullToSync)
        vm.onAction(GalleryAction.PullToSync)
        assertEquals(1, h.sync.calls)
    }

    @Test
    fun aDayShowsTheJournalEntriesOfThatLocalDateOnly() = runTest {
        h.index.put(
            entry("morning", "2026-11-17T08:00:00.000Z"),
            entry("evening", "2026-11-17T20:00:00.000Z"),
            entry("shifted", "2026-11-16T23:30:00.000Z", utcOffsetMinutes = 60),
            entry("before", "2026-11-16T09:00:00.000Z"),
            entry("trashed", "2026-11-17T10:00:00.000Z", trashed = true),
        )
        val (_, screen) = day("2026-11-17")
        assertEquals("2026-11-17", screen.value.date)
        assertEquals(TODAY, screen.value.grid.today)
        assertEquals(listOf("2026-11-17"), screen.value.grid.sections.map { it.key })
        assertEquals(listOf("evening", "morning", "shifted"), screen.value.grid.ids)
    }

    @Test
    fun anEmptyDayHasNoSections() = runTest {
        h.index.put(kept)
        val (_, screen) = day("2026-11-03")
        assertEquals(emptyList<GallerySection>(), screen.value.grid.sections)
    }

    @Test
    fun aDayUsesTheGalleryAttributeFiltersLikeV031() = runTest {
        h.index.put(entry("plain", "2026-11-17T08:00:00.000Z"), entry("clip", "2026-11-17T09:00:00.000Z", hasVideo = true))
        h.settings.value = defaults.copy(galleryFilters = defaults.galleryFilters.copy(video = TriState.INCLUDE))
        val (_, screen) = day("2026-11-17")
        assertEquals(listOf("clip"), screen.value.grid.ids)
    }

    @Test
    fun aDayIgnoresTheGalleryTagsAndSearch() = runTest {
        h.index.put(entry("plain", "2026-11-17T08:00:00.000Z"), entry("tagged", "2026-11-17T09:00:00.000Z", tags = listOf("a")))
        h.filter.set(GalleryFilters.EMPTY.copy(tags = listOf("a"), text = "nothing"))
        val (_, screen) = day("2026-11-17")
        assertEquals(listOf("tagged", "plain"), screen.value.grid.ids)
    }

    @Test
    fun aDayEntryOpensWithATapAndALongPressSelectsWithATick() = runTest {
        h.index.put(entry("morning", "2026-11-17T08:00:00.000Z"), entry("evening", "2026-11-17T20:00:00.000Z"))
        val (vm, screen) = day("2026-11-17")
        vm.onAction(GalleryAction.TileClick("morning"))
        vm.onAction(GalleryAction.TileLongClick("evening"))
        vm.onAction(GalleryAction.Back)
        assertEquals(listOf(GalleryEvent.Open(Route.Entry("morning")), GalleryEvent.Back), screen.events)
        assertEquals(listOf(HapticKind.SELECT), h.haptics)
        assertEquals(GallerySelection(active = true, ids = listOf("evening")), screen.value.grid.selection)
    }

    @Test
    fun aDayOffersTheJournalBulkSheetAndClearsTheSelectionOnceAnActionFinishes() = runTest {
        h.index.put(entry("morning", "2026-11-17T08:00:00.000Z", tags = listOf("a")), entry("evening", "2026-11-17T20:00:00.000Z"))
        val (vm, screen) = day("2026-11-17")
        vm.onAction(GalleryAction.SelectAll)
        vm.onAction(GalleryAction.OpenActions)
        val bulk = (screen.value.sheet as GallerySheet.Actions).bulk
        assertEquals(BulkMode.JOURNAL, bulk.mode)
        assertEquals(2, bulk.count)
        assertEquals(listOf("a"), bulk.tagChoices)
        vm.onAction(GalleryAction.BulkFavorite(true))
        assertEquals(listOf(BulkCall("setFavorite", listOf("evening", "morning"), true)), h.entries.calls)
        assertEquals(GallerySelection(), screen.value.grid.selection)
        assertEquals(GallerySnackbar.BulkDone(2, 0), screen.value.snackbar)
    }
}
