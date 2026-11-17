@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.sharing

import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.secure.AccountMode
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class SharedViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SharingHarness()

    private class Opened(val vm: SharedViewModel, val events: MutableList<SharedEvent>) {
        val state: SharedState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = SharedViewModel(h.deps)
        val events = mutableListOf<SharedEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun names(list: List<SharedJournal>?): List<String> = list.orEmpty().map { (it.name as CollectionName.Named).name }

    @Test
    fun aServerAccountWithSharingOnSeesBothSections() = runTest {
        val o = open()
        assertEquals(SharedAvailability.ENABLED, o.state.availability)
        assertEquals(emptyList<SharedJournal>(), o.state.mine)
        assertEquals(emptyList<SharedJournal>(), o.state.withMe)
        assertNull(o.state.dialog)
        assertFalse(o.state.refreshing)
    }

    @Test
    fun sharingOffOnTheServerOrNoKnownConfigShowsTheFlagOffNotice() = runTest {
        h.config.config = publicConfig(sharing = false, links = true)
        assertEquals(SharedAvailability.FLAG_OFF, open().state.availability)
        h.config.config = null
        assertEquals(SharedAvailability.FLAG_OFF, open().state.availability)
    }

    @Test
    fun aLocalAccountIsAskedToConnectAServerWhateverTheConfigSays() = runTest {
        h.accountMode.value = AccountMode.LOCAL
        assertEquals(SharedAvailability.LOCAL_ONLY, open().state.availability)
    }

    @Test
    fun aConfigArrivingLaterTurnsTheTabOn() = runTest {
        h.config.config = null
        val o = open()
        assertEquals(SharedAvailability.FLAG_OFF, o.state.availability)
        h.config.accept(publicConfig(sharing = true))
        runCurrent()
        assertEquals(SharedAvailability.ENABLED, o.state.availability)
        h.config.accept(publicConfig(sharing = false))
        runCurrent()
        assertEquals(SharedAvailability.FLAG_OFF, o.state.availability)
    }

    @Test
    fun journalsAreSplitByRoleWithTheirResolvedNamesOwnersAndEntryCounts() = runTest {
        h.picnicsWithTwoEntries()
        h.bobsJournalWithTwoEntries()
        h.journalRow(TRIPS, null)
        h.index.put("t1", listOf(HOME, TRIPS))
        h.index.put("t2", listOf(HOME, TRIPS), trashed = true)
        val o = open()
        assertEquals(
            listOf(journal(PICNICS, "Picnics", count = 2), journal(TRIPS, null, count = 1)).toSet(),
            o.state.mine.orEmpty().toSet(),
        )
        assertEquals(listOf(journal(FROM_BOB, "Picnics", CollectionRole.VIEWER, "bob", count = 2)), o.state.withMe)
    }

    @Test
    fun journalsAreSortedByNameWithTheDeviceCollationNotByCodePoint() = runTest {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            h.journalRow("zoojournal000000000001", "Zoo")
            h.journalRow("applejournal0000000001", "apple")
            h.journalRow("beachjournal0000000001", "Beach")
            h.journalRow("viewer1journal00000001", "zebra", CollectionRole.VIEWER, "bob")
            h.journalRow("viewer2journal00000001", "Alps", CollectionRole.VIEWER, "carol")
            val o = open()
            assertEquals(listOf("apple", "Beach", "Zoo"), names(o.state.mine))
            assertEquals(listOf("Alps", "zebra"), names(o.state.withMe))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun theListReloadsWhenTheIndexChanges() = runTest {
        h.picnicsWithTwoEntries()
        val o = open()
        assertEquals(2, o.state.mine!!.single().entryCount)
        h.index.put("e3", listOf(HOME, PICNICS))
        runCurrent()
        assertEquals(3, o.state.mine!!.single().entryCount)
    }

    @Test
    fun theListReloadsAfterASuccessfulSyncBringsNewJournals() = runTest {
        val o = open()
        assertEquals(emptyList<SharedJournal>(), o.state.withMe)
        h.journalRow(FROM_BOB, "Picnics", CollectionRole.VIEWER, "bob")
        h.syncedAt.value = 1_000L
        runCurrent()
        assertEquals(listOf(journal(FROM_BOB, "Picnics", CollectionRole.VIEWER, "bob")), o.state.withMe)
        h.rows.rows.clear()
        h.syncedAt.value = 2_000L
        runCurrent()
        assertEquals(emptyList<SharedJournal>(), o.state.withMe)
    }

    @Test
    fun tappingAJournalOpensItsScreen() = runTest {
        h.picnicsWithTwoEntries()
        val o = open()
        o.vm.onAction(SharedAction.Open(PICNICS))
        assertEquals(listOf<SharedEvent>(SharedEvent.Open(Route.Collection(PICNICS))), o.events)
    }

    @Test
    fun newJournalOpensAnEmptyNameAndCreateTrimsTheNameThenOpensTheNewJournal() = runTest {
        val o = open()
        o.vm.onAction(SharedAction.New)
        assertEquals(SharedDialog.New(name = "", busy = false, error = null), o.state.dialog)
        o.vm.onAction(SharedAction.NameChanged("  Picnics  "))
        assertEquals(SharedDialog.New(name = "  Picnics  ", busy = false, error = null), o.state.dialog)
        o.vm.onAction(SharedAction.Create)
        runCurrent()
        assertEquals(listOf(SharingCall(FakeSharingService.CREATE, null, listOf("Picnics"))), h.sharing.calls)
        assertNull(o.state.dialog)
        assertEquals(listOf<SharedEvent>(SharedEvent.Open(Route.Collection(h.sharing.nextId))), o.events)
        assertEquals(listOf("Picnics"), names(o.state.mine))
    }

    @Test
    fun aBlankNameCreatesNothing() = runTest {
        val o = open()
        o.vm.onAction(SharedAction.New)
        o.vm.onAction(SharedAction.NameChanged("   "))
        o.vm.onAction(SharedAction.Create)
        runCurrent()
        assertEquals(emptyList<SharingCall>(), h.sharing.calls)
        assertEquals(SharedDialog.New(name = "   ", busy = false, error = null), o.state.dialog)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun theNameIsCutToTheJournalNameLimit() = runTest {
        val o = open()
        o.vm.onAction(SharedAction.New)
        o.vm.onAction(SharedAction.NameChanged("n".repeat(COLLECTION_NAME_MAX_CHARS + 5)))
        assertEquals("n".repeat(COLLECTION_NAME_MAX_CHARS), (o.state.dialog as SharedDialog.New).name)
    }

    @Test
    fun theDialogIsBusyWhileCreatingAndAFailureKeepsItOpenWithTheTypedError() = runTest {
        val o = open()
        val gate = CompletableDeferred<Unit>()
        h.sharing.gate = gate
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        o.vm.onAction(SharedAction.New)
        o.vm.onAction(SharedAction.NameChanged("Picnics"))
        o.vm.onAction(SharedAction.Create)
        runCurrent()
        assertEquals(SharedDialog.New(name = "Picnics", busy = true, error = null), o.state.dialog)
        gate.complete(Unit)
        runCurrent()
        assertEquals(SharedDialog.New(name = "Picnics", busy = false, error = SharingMessage.Offline), o.state.dialog)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun retryingClearsTheEarlierError() = runTest {
        val o = open()
        h.sharing.failure = SharingException(SharingErrorReason.DISABLED)
        o.vm.onAction(SharedAction.New)
        o.vm.onAction(SharedAction.NameChanged("Picnics"))
        o.vm.onAction(SharedAction.Create)
        runCurrent()
        assertEquals(SharingMessage.FlagOff, (o.state.dialog as SharedDialog.New).error)
        val gate = CompletableDeferred<Unit>()
        h.sharing.gate = gate
        h.sharing.failure = null
        o.vm.onAction(SharedAction.Create)
        runCurrent()
        assertEquals(SharedDialog.New(name = "Picnics", busy = true, error = null), o.state.dialog)
        gate.complete(Unit)
        runCurrent()
        assertNull(o.state.dialog)
    }

    @Test
    fun dismissClosesTheDialogWithoutCreating() = runTest {
        val o = open()
        o.vm.onAction(SharedAction.New)
        o.vm.onAction(SharedAction.NameChanged("Picnics"))
        o.vm.onAction(SharedAction.Dismiss)
        assertNull(o.state.dialog)
        assertEquals(emptyList<SharingCall>(), h.sharing.calls)
        o.vm.onAction(SharedAction.New)
        assertEquals(SharedDialog.New(name = "", busy = false, error = null), o.state.dialog)
    }

    @Test
    fun pullToRefreshRunsASyncAndShowsTheSpinnerUntilItEnds() = runTest {
        val o = open()
        val gate = CompletableDeferred<Unit>()
        h.syncGate = gate
        o.vm.onAction(SharedAction.Refresh)
        runCurrent()
        assertEquals(1, h.syncs)
        assertTrue(o.state.refreshing)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.state.refreshing)
    }
}
