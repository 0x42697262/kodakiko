@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.sharing

import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
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
class ShareSheetViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SharingHarness()

    private class Opened(val vm: ShareSheetViewModel, val events: MutableList<ShareSheetEvent>) {
        val state: ShareSheetState get() = vm.state.value
        val apps: ToAppsState get() = state.apps
        val kodakiko: InKodakikoState get() = state.kodakiko
    }

    private fun TestScope.open(entryIds: List<String> = listOf("pair"), inKodakiko: Boolean = true): Opened {
        val vm = ShareSheetViewModel(h.deps, entryIds, inKodakiko)
        val events = mutableListOf<ShareSheetEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun TestScope.toApps(entryIds: List<String> = listOf("pair")): Opened {
        val o = open(entryIds)
        o.vm.onAction(ShareSheetAction.SelectTab(ShareTab.TO_APPS))
        runCurrent()
        return o
    }

    private val none = AddEntriesResult(added = emptyList(), alreadyIn = emptyList(), notSynced = emptyList(), failed = emptyList())

    @Test
    fun inKodakikoComesFirstWhenTheServerSharesAndTheCallerAllowsIt() = runTest {
        val o = open()
        assertTrue(o.state.inKodakiko)
        assertEquals(ShareTab.IN_KODAKIKO, o.state.tab)
        assertEquals(InKodakikoPanel.MENU, o.kodakiko.panel)
        assertTrue(o.kodakiko.sharingEnabled)
        assertTrue(o.kodakiko.linksEnabled)
        assertNull(o.kodakiko.outcome)
        assertFalse(o.kodakiko.busy)
    }

    @Test
    fun onlyToAppsShowsForALocalAccountACallerThatSaysNoOrAServerWithBothFeaturesOff() = runTest {
        h.accountMode.value = AccountMode.LOCAL
        open().let {
            assertFalse(it.state.inKodakiko)
            assertEquals(ShareTab.TO_APPS, it.state.tab)
        }
        h.accountMode.value = AccountMode.SERVER
        open(inKodakiko = false).let {
            assertFalse(it.state.inKodakiko)
            assertEquals(ShareTab.TO_APPS, it.state.tab)
        }
        h.config.config = publicConfig(sharing = false, links = false)
        open().let {
            assertFalse(it.state.inKodakiko)
            assertEquals(ShareTab.TO_APPS, it.state.tab)
        }
        h.config.config = null
        assertFalse(open().state.inKodakiko)
    }

    @Test
    fun eitherFeatureAloneKeepsInKodakikoWithTheOtherActionOff() = runTest {
        h.config.config = publicConfig(sharing = true, links = false)
        open().let {
            assertTrue(it.state.inKodakiko)
            assertTrue(it.kodakiko.sharingEnabled)
            assertFalse(it.kodakiko.linksEnabled)
        }
        h.config.config = publicConfig(sharing = false, links = true)
        open().let {
            assertTrue(it.state.inKodakiko)
            assertFalse(it.kodakiko.sharingEnabled)
            assertTrue(it.kodakiko.linksEnabled)
        }
    }

    @Test
    fun selectingATabSwitchesButToAppsIsTheOnlyTabWithoutInKodakiko() = runTest {
        val o = open()
        o.vm.onAction(ShareSheetAction.SelectTab(ShareTab.TO_APPS))
        assertEquals(ShareTab.TO_APPS, o.state.tab)
        o.vm.onAction(ShareSheetAction.SelectTab(ShareTab.IN_KODAKIKO))
        assertEquals(ShareTab.IN_KODAKIKO, o.state.tab)
        val local = open(inKodakiko = false)
        local.vm.onAction(ShareSheetAction.SelectTab(ShareTab.IN_KODAKIKO))
        assertEquals(ShareTab.TO_APPS, local.state.tab)
    }

    @Test
    fun toAppsListsEveryItemOfTheChosenEntriesAllCheckedWithTheirCountAndSize() = runTest {
        h.pairEntry()
        h.shareOut.store("solo", "solo note", video("v1", 20 * MB))
        val o = toApps(listOf("pair", "solo"))
        assertEquals(listOf("p1", "p2", "v1"), o.apps.items!!.map { it.media.mediaId })
        assertEquals(setOf("p1", "p2", "v1"), o.apps.chosen)
        assertEquals(ShareSelection(count = 3, bytes = 25 * MB, warn = false), o.apps.selection)
        assertEquals(ShareTextOption.NONE, o.apps.option)
        assertFalse(o.apps.busy)
        assertFalse(o.apps.failed)
        assertTrue(listOf("pair", "solo") in h.shareOut.itemRequests)
    }

    @Test
    fun u39UncheckingAnItemUpdatesTheCountAndCheckingItAgainRestoresIt() = runTest {
        h.pairEntry()
        val o = toApps()
        assertEquals(2, o.apps.selection.count)
        o.vm.onAction(ShareSheetAction.Toggle("p2"))
        assertEquals(setOf("p1"), o.apps.chosen)
        assertEquals(ShareSelection(count = 1, bytes = 2 * MB, warn = false), o.apps.selection)
        o.vm.onAction(ShareSheetAction.Toggle("p1"))
        assertEquals(ShareSelection(count = 0, bytes = 0, warn = false), o.apps.selection)
        o.vm.onAction(ShareSheetAction.Toggle("p2"))
        assertEquals(setOf("p2"), o.apps.chosen)
        assertEquals(3 * MB, o.apps.selection.bytes)
    }

    @Test
    fun moreThan500MegabytesWarnsAndExactly500DoesNot() = runTest {
        h.shareOut.store("big", "", video("v1", 250 * MB), video("v2", 250 * MB), photo("p1", 1))
        val o = toApps(listOf("big"))
        assertEquals(ShareSelection(count = 3, bytes = 500 * MB + 1, warn = true), o.apps.selection)
        o.vm.onAction(ShareSheetAction.Toggle("p1"))
        assertEquals(ShareSelection(count = 2, bytes = 500 * MB, warn = false), o.apps.selection)
    }

    @Test
    fun sendSharesTheCheckedItemsWithTheChosenTextOption() = runTest {
        h.pairEntry()
        val o = toApps()
        o.vm.onAction(ShareSheetAction.Toggle("p2"))
        o.vm.onAction(ShareSheetAction.ChooseText(ShareTextOption.CAPTIONS_AND_NOTE))
        assertEquals(ShareTextOption.CAPTIONS_AND_NOTE, o.apps.option)
        val gate = CompletableDeferred<Unit>()
        h.shareOut.shareGate = gate
        o.vm.onAction(ShareSheetAction.Send)
        runCurrent()
        assertTrue(o.apps.busy)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.apps.busy)
        val call = h.shareOut.shared.single()
        assertEquals(listOf("p1", "p2"), call.items.map { it.media.mediaId })
        assertEquals(setOf("p1"), call.chosen)
        assertEquals(ShareTextOption.CAPTIONS_AND_NOTE, call.option)
        assertFalse(o.apps.failed)
    }

    @Test
    fun sendWithNothingCheckedSharesNothing() = runTest {
        h.pairEntry()
        val o = toApps()
        o.vm.onAction(ShareSheetAction.Toggle("p1"))
        o.vm.onAction(ShareSheetAction.Toggle("p2"))
        o.vm.onAction(ShareSheetAction.Send)
        runCurrent()
        assertTrue(h.shareOut.shared.isEmpty())
    }

    @Test
    fun aFailedShareSaysSoAndTheNextAttemptClearsIt() = runTest {
        h.pairEntry()
        val o = toApps()
        h.shareOut.shareFailure = IllegalStateException("no space")
        o.vm.onAction(ShareSheetAction.Send)
        runCurrent()
        assertTrue(o.apps.failed)
        assertFalse(o.apps.busy)
        h.shareOut.shareFailure = null
        val gate = CompletableDeferred<Unit>()
        h.shareOut.shareGate = gate
        o.vm.onAction(ShareSheetAction.Send)
        runCurrent()
        assertFalse(o.apps.failed)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.apps.failed)
    }

    @Test
    fun itemsThatCannotBeReadLeaveAnEmptyPickerThatSaysItFailed() = runTest {
        h.shareOut.itemsFailure = IllegalStateException("locked")
        val o = toApps()
        assertNull(o.apps.items)
        assertTrue(o.apps.failed)
    }

    @Test
    fun addToListsOnlyOwnJournalsSortedByName() = runTest {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            h.journalRow(TRIPS, "trips")
            h.journalRow(PICNICS, "Picnics")
            h.journalRow(FROM_BOB, "Bob's", CollectionRole.VIEWER, "bob")
            val o = open()
            o.vm.onAction(ShareSheetAction.AddTo)
            runCurrent()
            assertEquals(InKodakikoPanel.JOURNALS, o.kodakiko.panel)
            assertEquals(
                listOf(JournalChoice(PICNICS, CollectionName.Named("Picnics")), JournalChoice(TRIPS, CollectionName.Named("trips"))),
                o.kodakiko.journals,
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun addingToAJournalAddsTheEntriesAndReportsTheResultBackOnTheMenu() = runTest {
        h.journalRow(PICNICS, "Picnics")
        val result = AddEntriesResult(added = listOf("e1"), alreadyIn = listOf("e2"), notSynced = listOf("e3"), failed = emptyList())
        h.sharing.addResult = result
        val o = open(listOf("e1", "e2", "e3"))
        o.vm.onAction(ShareSheetAction.AddTo)
        val gate = CompletableDeferred<Unit>()
        h.sharing.gate = gate
        o.vm.onAction(ShareSheetAction.AddToJournal(PICNICS))
        runCurrent()
        assertTrue(o.kodakiko.busy)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(SharingCall(FakeSharingService.ADD, PICNICS, listOf("e1", "e2", "e3"))), h.sharing.calls)
        assertEquals(ShareEntriesOutcome.Added(result), o.kodakiko.outcome)
        assertEquals(InKodakikoPanel.MENU, o.kodakiko.panel)
        assertFalse(o.kodakiko.busy)
    }

    @Test
    fun aFailedAddKeepsTheJournalListWithTheTypedMessage() = runTest {
        h.journalRow(PICNICS, "Picnics")
        val o = open()
        h.sharing.failure = SharingException(SharingErrorReason.OFFLINE)
        o.vm.onAction(ShareSheetAction.AddTo)
        o.vm.onAction(ShareSheetAction.AddToJournal(PICNICS))
        runCurrent()
        assertEquals(ShareEntriesOutcome.Failed(SharingMessage.Offline), o.kodakiko.outcome)
        assertEquals(InKodakikoPanel.JOURNALS, o.kodakiko.panel)
    }

    @Test
    fun aNewJournalIsCreatedWithTheTrimmedNameThenTheEntriesAreAddedToIt() = runTest {
        h.sharing.addResult = none.copy(added = listOf("pair"))
        val o = open()
        o.vm.onAction(ShareSheetAction.AddTo)
        o.vm.onAction(ShareSheetAction.NewJournal)
        assertEquals(InKodakikoPanel.NEW, o.kodakiko.panel)
        assertEquals("", o.kodakiko.name)
        o.vm.onAction(ShareSheetAction.NameChanged(" Picnics "))
        assertEquals(" Picnics ", o.kodakiko.name)
        o.vm.onAction(ShareSheetAction.CreateJournal)
        runCurrent()
        assertEquals(
            listOf(
                SharingCall(FakeSharingService.CREATE, null, listOf("Picnics")),
                SharingCall(FakeSharingService.ADD, h.sharing.nextId, listOf("pair")),
            ),
            h.sharing.calls,
        )
        assertEquals(ShareEntriesOutcome.Added(none.copy(added = listOf("pair"))), o.kodakiko.outcome)
        assertEquals(InKodakikoPanel.MENU, o.kodakiko.panel)
    }

    @Test
    fun aBlankNewJournalNameCreatesNothingAndAFailedCreateStaysOnTheForm() = runTest {
        val o = open()
        o.vm.onAction(ShareSheetAction.AddTo)
        o.vm.onAction(ShareSheetAction.NewJournal)
        o.vm.onAction(ShareSheetAction.NameChanged("  "))
        o.vm.onAction(ShareSheetAction.CreateJournal)
        runCurrent()
        assertEquals(emptyList<SharingCall>(), h.sharing.calls)
        h.sharing.failure = SharingException(SharingErrorReason.DISABLED)
        o.vm.onAction(ShareSheetAction.NameChanged("Picnics"))
        o.vm.onAction(ShareSheetAction.CreateJournal)
        runCurrent()
        assertEquals(listOf(FakeSharingService.CREATE), h.sharing.kinds())
        assertEquals(ShareEntriesOutcome.Failed(SharingMessage.FlagOff), o.kodakiko.outcome)
        assertEquals(InKodakikoPanel.NEW, o.kodakiko.panel)
        assertEquals("Picnics", o.kodakiko.name)
    }

    @Test
    fun addToDoesNothingWhenSharingIsOff() = runTest {
        h.config.config = publicConfig(sharing = false, links = true)
        val o = open()
        o.vm.onAction(ShareSheetAction.AddTo)
        assertEquals(InKodakikoPanel.MENU, o.kodakiko.panel)
    }

    @Test
    fun createPublicLinkReplacesTheSheetWithTheLinkScreenForTheseEntries() = runTest {
        val o = open(listOf("e1", "e2"))
        o.vm.onAction(ShareSheetAction.CreateLink)
        assertEquals(listOf<ShareSheetEvent>(ShareSheetEvent.Replace(Route.LinkNew(listOf("e1", "e2")))), o.events)
    }

    @Test
    fun createPublicLinkDoesNothingWhenLinksAreOff() = runTest {
        h.config.config = publicConfig(sharing = true, links = false)
        val o = open()
        o.vm.onAction(ShareSheetAction.CreateLink)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun dismissClosesTheSheet() = runTest {
        val o = open()
        o.vm.onAction(ShareSheetAction.Dismiss)
        assertEquals(listOf<ShareSheetEvent>(ShareSheetEvent.Close), o.events)
    }

    @Test
    fun f18OneEntryWithOnlyToAppsGoesStraightToTheChooserWithAllItsMediaAndNoText() = runTest {
        h.pairEntry()
        h.accountMode.value = AccountMode.LOCAL
        val o = open()
        val call = h.shareOut.shared.single()
        assertEquals(listOf("p1", "p2"), call.items.map { it.media.mediaId })
        assertEquals(setOf("p1", "p2"), call.chosen)
        assertEquals(ShareTextOption.NONE, call.option)
        assertTrue(o.state.direct)
        assertEquals(listOf<ShareSheetEvent>(ShareSheetEvent.Close), o.events)
    }

    @Test
    fun f18AServerWithSharingAndLinksOffAlsoGoesStraightToTheChooser() = runTest {
        h.pairEntry()
        h.config.config = publicConfig(sharing = false, links = false)
        val o = open()
        assertEquals(1, h.shareOut.shared.size)
        assertEquals(listOf<ShareSheetEvent>(ShareSheetEvent.Close), o.events)
    }

    @Test
    fun f18TheSheetStaysForSeveralEntriesOrWhenInKodakikoIsPossible() = runTest {
        h.pairEntry()
        h.shareOut.store("solo", "", video("v1", 20 * MB))
        open().let {
            assertFalse(it.state.direct)
            assertTrue(it.events.isEmpty())
        }
        h.accountMode.value = AccountMode.LOCAL
        open(listOf("pair", "solo")).let {
            assertFalse(it.state.direct)
            assertEquals(listOf("p1", "p2", "v1"), it.apps.items!!.map { item -> item.media.mediaId })
            assertTrue(it.events.isEmpty())
        }
        assertTrue(h.shareOut.shared.isEmpty())
    }

    @Test
    fun f18AnEntryWithoutMediaShowsTheSheetInstead() = runTest {
        h.shareOut.store("empty", "")
        h.accountMode.value = AccountMode.LOCAL
        val o = open(listOf("empty"))
        assertFalse(o.state.direct)
        assertTrue(h.shareOut.shared.isEmpty())
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun f18AFailedDirectShareShowsTheSheetWithTheError() = runTest {
        h.pairEntry()
        h.accountMode.value = AccountMode.LOCAL
        h.shareOut.shareFailure = IllegalStateException("no space")
        val o = open()
        assertFalse(o.state.direct)
        assertTrue(o.apps.failed)
        assertTrue(o.events.isEmpty())
    }

    @Test
    fun f18ItemsThatCannotBeReadShowTheSheetWithTheError() = runTest {
        h.accountMode.value = AccountMode.LOCAL
        h.shareOut.itemsFailure = IllegalStateException("damaged")
        val o = open()
        assertFalse(o.state.direct)
        assertTrue(o.apps.failed)
        assertTrue(h.shareOut.shared.isEmpty())
    }
}
