@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.sharing

import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sharing.SharingErrorReason
import dev.kodakiko.service.sharing.SharingException
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
class CollectionViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SharingHarness()

    private class Opened(val vm: CollectionViewModel, val events: MutableList<CollectionEvent>) {
        val state: CollectionState get() = vm.state.value
    }

    private fun TestScope.open(collectionId: String): Opened {
        val vm = CollectionViewModel(h.deps, collectionId)
        val events = mutableListOf<CollectionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun TestScope.owner(): Opened {
        h.picnicsWithTwoEntries()
        return open(PICNICS)
    }

    private fun TestScope.recipient(): Opened {
        h.bobsJournalWithTwoEntries()
        return open(FROM_BOB)
    }

    @Test
    fun anOwnersJournalShowsItsNameAndItsEntriesNewestFirst() = runTest {
        h.index.put("other", listOf(HOME))
        h.index.put("binned", listOf(HOME, PICNICS), trashed = true)
        val o = owner()
        assertTrue(o.state.loaded)
        assertEquals(journal(PICNICS, "Picnics", count = 2), o.state.journal)
        assertEquals(listOf(CollectionTile("e2", "e2-cover"), CollectionTile("e1", "e1-cover")), o.state.tiles)
        assertFalse(o.state.selecting)
        assertEquals(emptyList<String>(), o.state.selected)
        assertNull(o.state.sheet)
    }

    @Test
    fun aRecipientSeesTheOwnerOfTheJournal() = runTest {
        val o = recipient()
        assertEquals(journal(FROM_BOB, "Picnics", CollectionRole.VIEWER, "bob", count = 2), o.state.journal)
        assertEquals(listOf("b2", "b1"), o.state.tiles.map { it.entryId })
    }

    @Test
    fun aJournalThatIsNoLongerOnTheDeviceIsLoadedAsMissing() = runTest {
        val o = open("gonejournal00000000001")
        assertTrue(o.state.loaded)
        assertNull(o.state.journal)
        assertEquals(emptyList<CollectionTile>(), o.state.tiles)
    }

    @Test
    fun aJournalRemovedBySyncWhileOpenTurnsMissing() = runTest {
        val o = recipient()
        h.rows.rows.clear()
        h.syncedAt.value = 5_000L
        runCurrent()
        assertNull(o.state.journal)
    }

    @Test
    fun newEntriesInTheIndexAppearInTheGrid() = runTest {
        val o = owner()
        h.index.put("e3", listOf(HOME, PICNICS), takenAtUtc = "2026-05-05T08:00:00.000Z")
        runCurrent()
        assertEquals(listOf("e3", "e2", "e1"), o.state.tiles.map { it.entryId })
        assertEquals(3, o.state.journal!!.entryCount)
    }

    @Test
    fun tappingATileOpensTheEntry() = runTest {
        val o = recipient()
        o.vm.onAction(CollectionAction.TileClick("b1"))
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Open(Route.Entry("b1"))), o.events)
    }

    @Test
    fun theOwnersMenuShareWithOpensTheShareScreen() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.More)
        assertEquals(CollectionSheet.Menu, o.state.sheet)
        o.vm.onAction(CollectionAction.ShareWith)
        assertNull(o.state.sheet)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Open(Route.CollectionShare(PICNICS))), o.events)
    }

    @Test
    fun renameStartsFromTheCurrentNameAndSavesTheTrimmedNameThenClearsTheNameCache() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.More)
        o.vm.onAction(CollectionAction.Rename)
        assertEquals(CollectionSheet.Rename(name = "Picnics", busy = false, error = null), o.state.sheet)
        o.vm.onAction(CollectionAction.NameChanged("  Summer picnics "))
        o.vm.onAction(CollectionAction.Save)
        runCurrent()
        assertEquals(listOf(SharingCall(FakeSharingService.RENAME, PICNICS, listOf("Summer picnics"))), h.sharing.calls)
        assertEquals(1, h.names.clears)
        assertNull(o.state.sheet)
        assertEquals(CollectionName.Named("Summer picnics"), o.state.journal!!.name)
    }

    @Test
    fun renamingAnUnnamedJournalStartsEmptyAndTheNameIsCutToTheLimit() = runTest {
        h.journalRow(TRIPS, null)
        val o = open(TRIPS)
        o.vm.onAction(CollectionAction.Rename)
        assertEquals(CollectionSheet.Rename(name = "", busy = false, error = null), o.state.sheet)
        o.vm.onAction(CollectionAction.NameChanged("x".repeat(COLLECTION_NAME_MAX_CHARS + 1)))
        assertEquals("x".repeat(COLLECTION_NAME_MAX_CHARS), (o.state.sheet as CollectionSheet.Rename).name)
    }

    @Test
    fun aBlankRenameSavesNothing() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.Rename)
        o.vm.onAction(CollectionAction.NameChanged("  "))
        o.vm.onAction(CollectionAction.Save)
        runCurrent()
        assertEquals(emptyList<SharingCall>(), h.sharing.calls)
        assertEquals(CollectionSheet.Rename(name = "  ", busy = false, error = null), o.state.sheet)
    }

    @Test
    fun aFailedRenameStaysOpenWithItsMessage() = runTest {
        val o = owner()
        val gate = CompletableDeferred<Unit>()
        h.sharing.gate = gate
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        o.vm.onAction(CollectionAction.Rename)
        o.vm.onAction(CollectionAction.Save)
        runCurrent()
        assertEquals(CollectionSheet.Rename(name = "Picnics", busy = true, error = null), o.state.sheet)
        gate.complete(Unit)
        runCurrent()
        assertEquals(CollectionSheet.Rename(name = "Picnics", busy = false, error = SharingMessage.Offline), o.state.sheet)
        assertEquals(0, h.names.clears)
    }

    @Test
    fun deleteAsksFirstThenDeletesAndLeavesTheScreen() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.More)
        o.vm.onAction(CollectionAction.Delete)
        assertEquals(CollectionSheet.ConfirmDelete(busy = false, error = null), o.state.sheet)
        assertEquals(emptyList<SharingCall>(), h.sharing.calls)
        o.vm.onAction(CollectionAction.ConfirmDelete)
        runCurrent()
        assertEquals(listOf(SharingCall(FakeSharingService.DELETE, PICNICS)), h.sharing.calls)
        assertNull(o.state.sheet)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Back), o.events)
    }

    @Test
    fun aFailedDeleteStaysWithItsMessage() = runTest {
        val o = owner()
        h.sharing.failure = SharingException(SharingErrorReason.NOT_OWNER)
        o.vm.onAction(CollectionAction.Delete)
        o.vm.onAction(CollectionAction.ConfirmDelete)
        runCurrent()
        assertEquals(CollectionSheet.ConfirmDelete(busy = false, error = SharingMessage.Failed), o.state.sheet)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun aRecipientLeavesAfterConfirmingAndTheScreenCloses() = runTest {
        val o = recipient()
        o.vm.onAction(CollectionAction.More)
        assertEquals(CollectionSheet.Menu, o.state.sheet)
        o.vm.onAction(CollectionAction.Leave)
        assertEquals(CollectionSheet.ConfirmLeave(busy = false, error = null), o.state.sheet)
        val gate = CompletableDeferred<Unit>()
        h.sharing.gate = gate
        o.vm.onAction(CollectionAction.ConfirmLeave)
        runCurrent()
        assertEquals(CollectionSheet.ConfirmLeave(busy = true, error = null), o.state.sheet)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(SharingCall(FakeSharingService.LEAVE, FROM_BOB)), h.sharing.calls)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Back), o.events)
    }

    @Test
    fun leavingOfflineStaysWithTheOfflineMessage() = runTest {
        val o = recipient()
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        o.vm.onAction(CollectionAction.Leave)
        o.vm.onAction(CollectionAction.ConfirmLeave)
        runCurrent()
        assertEquals(CollectionSheet.ConfirmLeave(busy = false, error = SharingMessage.Offline), o.state.sheet)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun aRecipientCannotSelectEntries() = runTest {
        val o = recipient()
        o.vm.onAction(CollectionAction.TileLongClick("b1"))
        assertFalse(o.state.selecting)
        assertEquals(emptyList<String>(), o.state.selected)
        o.vm.onAction(CollectionAction.OpenActions)
        assertNull(o.state.sheet)
    }

    @Test
    fun theOwnerSelectsWithALongPressAndTapsToggleWhileSelecting() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.TileLongClick("e2"))
        assertTrue(o.state.selecting)
        assertEquals(listOf("e2"), o.state.selected)
        o.vm.onAction(CollectionAction.TileClick("e1"))
        assertEquals(listOf("e2", "e1"), o.state.selected)
        o.vm.onAction(CollectionAction.TileClick("e2"))
        assertEquals(listOf("e1"), o.state.selected)
        o.vm.onAction(CollectionAction.TileClick("e1"))
        assertTrue(o.state.selecting)
        assertEquals(emptyList<String>(), o.state.selected)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun actionsNeedASelectionAndRemoveFromThisJournalRemovesTheSelectedEntries() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.TileLongClick("e2"))
        o.vm.onAction(CollectionAction.TileClick("e2"))
        o.vm.onAction(CollectionAction.OpenActions)
        assertNull(o.state.sheet)
        o.vm.onAction(CollectionAction.TileClick("e1"))
        o.vm.onAction(CollectionAction.TileClick("e2"))
        o.vm.onAction(CollectionAction.OpenActions)
        assertEquals(CollectionSheet.Actions(busy = false, error = null), o.state.sheet)
        o.vm.onAction(CollectionAction.RemoveFromJournal)
        runCurrent()
        assertEquals(listOf(SharingCall(FakeSharingService.REMOVE, PICNICS, listOf("e1", "e2"))), h.sharing.calls)
        assertNull(o.state.sheet)
        assertFalse(o.state.selecting)
        assertEquals(emptyList<String>(), o.state.selected)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Removed(2)), o.events)
    }

    @Test
    fun aFailedRemoveKeepsTheSheetAndTheSelection() = runTest {
        val o = owner()
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        o.vm.onAction(CollectionAction.TileLongClick("e1"))
        o.vm.onAction(CollectionAction.OpenActions)
        o.vm.onAction(CollectionAction.RemoveFromJournal)
        runCurrent()
        assertEquals(CollectionSheet.Actions(busy = false, error = SharingMessage.Offline), o.state.sheet)
        assertEquals(listOf("e1"), o.state.selected)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun closingTheActionsSheetEndsTheSelection() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.TileLongClick("e1"))
        o.vm.onAction(CollectionAction.OpenActions)
        o.vm.onAction(CollectionAction.Dismiss)
        assertNull(o.state.sheet)
        assertFalse(o.state.selecting)
        assertEquals(emptyList<String>(), o.state.selected)
    }

    @Test
    fun closingTheMenuKeepsEverythingElse() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.More)
        o.vm.onAction(CollectionAction.Dismiss)
        assertNull(o.state.sheet)
        assertTrue(o.events.isEmpty())
        assertEquals(emptyList<SharingCall>(), h.sharing.calls)
    }

    @Test
    fun backEndsTheSelectionFirstThenLeavesTheScreen() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.TileLongClick("e1"))
        o.vm.onAction(CollectionAction.Back)
        assertFalse(o.state.selecting)
        assertTrue(o.events.isEmpty())
        o.vm.onAction(CollectionAction.Back)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Back), o.events)
    }

    @Test
    fun clearSelectionEndsSelecting() = runTest {
        val o = owner()
        o.vm.onAction(CollectionAction.TileLongClick("e1"))
        o.vm.onAction(CollectionAction.ClearSelection)
        assertFalse(o.state.selecting)
        assertEquals(emptyList<String>(), o.state.selected)
    }
}
