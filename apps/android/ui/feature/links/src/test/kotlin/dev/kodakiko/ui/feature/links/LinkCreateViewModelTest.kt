@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.links

import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.contract.LinkExpiry
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.service.links.CreateLinkInput
import dev.kodakiko.service.links.CreateLinkProgress
import dev.kodakiko.service.links.CreateLinkStep
import dev.kodakiko.service.links.LinkInputException
import dev.kodakiko.service.links.LinkInputReason
import dev.kodakiko.service.links.StripFailedException
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
class LinkCreateViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = LinksHarness()

    private class Opened(val vm: LinkCreateViewModel, val events: MutableList<LinkCreateEvent>) {
        val state: LinkCreateState get() = vm.state.value
    }

    private fun TestScope.open(entryIds: List<String> = listOf(ENTRY_A)): Opened {
        val vm = LinkCreateViewModel(h.deps, entryIds)
        val events = mutableListOf<LinkCreateEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private val allThree = setOf("photogps", "videogpsmov", "video20s")

    @Test
    fun everyChosenEntryIsASectionWithItsDateAndFirstNoteLineAndEveryItemStartsChecked() = runTest {
        h.u18Entry()
        h.secondEntry()
        val o = open(listOf(ENTRY_A, ENTRY_B))
        assertEquals(listOf(ENTRY_A, ENTRY_B), h.reads)
        assertEquals(
            listOf(
                LinkSection(
                    entryId = ENTRY_A,
                    heading = "2026-05-05 Secret picnic spot behind the old mill",
                    media = listOf(
                        LinkMedia("photogps", 0, MediaKind.PHOTO),
                        LinkMedia("videogpsmov", 1, MediaKind.VIDEO),
                        LinkMedia("video20s", 2, MediaKind.VIDEO),
                    ),
                ),
                LinkSection(entryId = ENTRY_B, heading = "2026-05-06", media = listOf(LinkMedia("lake", 3, MediaKind.PHOTO))),
            ),
            o.state.sections,
        )
        assertEquals(allThree + "lake", o.state.selected)
    }

    @Test
    fun theFormStartsWithTheDefaultTitleNotesOnSevenDaysAndNothingRunning() = runTest {
        h.u18Entry()
        val o = open()
        assertEquals(DEFAULT_TITLE, o.state.title)
        assertTrue(o.state.includeNotes)
        assertEquals(LinkExpiry.SEVEN_DAYS, o.state.expiry)
        assertNull(o.state.progress)
        assertNull(o.state.result)
        assertNull(o.state.error)
        assertFalse(o.state.copied)
    }

    @Test
    fun theEstimateIsTheOriginalsPlusThumbnailsOfTheCheckedItems() = runTest {
        h.u18Entry()
        val o = open()
        assertEquals(33 * MB + 100 * KB, o.state.estimateBytes)
        o.vm.onAction(LinkCreateAction.Toggle("videogpsmov"))
        assertEquals(13 * MB + 70 * KB, o.state.estimateBytes)
        o.vm.onAction(LinkCreateAction.SelectNone)
        assertEquals(0L, o.state.estimateBytes)
    }

    @Test
    fun anEntryThatCannotBeReadLeavesNothingToLink() = runTest {
        h.u18Entry()
        h.readFailure = IllegalStateException("locked")
        val o = open()
        assertEquals(emptyList<LinkSection>(), o.state.sections)
        assertEquals(emptySet<String>(), o.state.selected)
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertTrue(h.links.created.isEmpty())
    }

    @Test
    fun togglesAndSelectAllNoneWorkPerItemPerEntryAndOverall() = runTest {
        h.u18Entry()
        h.secondEntry()
        val o = open(listOf(ENTRY_A, ENTRY_B))
        o.vm.onAction(LinkCreateAction.Toggle("lake"))
        assertEquals(allThree, o.state.selected)
        o.vm.onAction(LinkCreateAction.Toggle("lake"))
        assertEquals(allThree + "lake", o.state.selected)
        o.vm.onAction(LinkCreateAction.SelectEntry(ENTRY_A, on = false))
        assertEquals(setOf("lake"), o.state.selected)
        o.vm.onAction(LinkCreateAction.SelectEntry(ENTRY_A, on = true))
        assertEquals(allThree + "lake", o.state.selected)
        o.vm.onAction(LinkCreateAction.SelectNone)
        assertEquals(emptySet<String>(), o.state.selected)
        o.vm.onAction(LinkCreateAction.SelectEntry(ENTRY_B, on = true))
        assertEquals(setOf("lake"), o.state.selected)
        o.vm.onAction(LinkCreateAction.SelectAll)
        assertEquals(allThree + "lake", o.state.selected)
    }

    @Test
    fun titleNotesAndExpiryFollowTheForm() = runTest {
        h.u18Entry()
        val o = open()
        o.vm.onAction(LinkCreateAction.TitleChanged("Picnic"))
        assertEquals("Picnic", o.state.title)
        o.vm.onAction(LinkCreateAction.TitleChanged("t".repeat(COLLECTION_NAME_MAX_CHARS + 3)))
        assertEquals("t".repeat(COLLECTION_NAME_MAX_CHARS), o.state.title)
        o.vm.onAction(LinkCreateAction.ToggleNotes)
        assertFalse(o.state.includeNotes)
        o.vm.onAction(LinkCreateAction.ToggleNotes)
        assertTrue(o.state.includeNotes)
        for (expiry in LinkExpiry.entries) {
            o.vm.onAction(LinkCreateAction.ChooseExpiry(expiry))
            assertEquals(expiry, o.state.expiry)
        }
    }

    @Test
    fun u18CreateSendsTheCheckedItemsTheTitleTheExpiryAndTheNotesChoice() = runTest {
        h.u18Entry()
        val o = open()
        o.vm.onAction(LinkCreateAction.Toggle("videogpsmov"))
        o.vm.onAction(LinkCreateAction.ToggleNotes)
        o.vm.onAction(LinkCreateAction.TitleChanged(" Picnic "))
        o.vm.onAction(LinkCreateAction.ChooseExpiry(LinkExpiry.ONE_VIEW))
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertEquals(
            listOf(
                CreateLinkInput(
                    entryIds = listOf(ENTRY_A),
                    mediaIds = setOf("photogps", "video20s"),
                    title = " Picnic ",
                    expiry = LinkExpiry.ONE_VIEW,
                    includeNotes = false,
                ),
            ),
            h.links.created,
        )
        assertEquals(h.links.result, o.state.result)
        assertNull(o.state.progress)
        assertNull(o.state.error)
    }

    @Test
    fun progressStartsAtPreparingTheFirstEntryAndFollowsTheService() = runTest {
        h.u18Entry()
        h.secondEntry()
        val gate = CompletableDeferred<Unit>()
        h.links.createGate = gate
        val o = open(listOf(ENTRY_A, ENTRY_B))
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertEquals(CreateLinkProgress(CreateLinkStep.PREPARING, 0, 2, 0, 0), o.state.progress)
        gate.complete(Unit)
        runCurrent()
        assertNull(o.state.progress)
    }

    @Test
    fun theServicesProgressReportsReachTheState() = runTest {
        h.u18Entry()
        val uploading = CreateLinkProgress(CreateLinkStep.UPLOADING, 1, 1, 3 * MB, 13 * MB)
        h.links.progressSteps = listOf(CreateLinkProgress(CreateLinkStep.PREPARING, 0, 1, 0, 0), uploading)
        val gate = CompletableDeferred<Unit>()
        h.links.progressGate = gate
        val o = open()
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertEquals(uploading, o.state.progress)
        gate.complete(Unit)
        runCurrent()
        assertNull(o.state.progress)
        assertEquals(h.links.result, o.state.result)
    }

    @Test
    fun createIsIgnoredWhileRunningWithNothingCheckedOrWithABlankTitle() = runTest {
        h.u18Entry()
        val gate = CompletableDeferred<Unit>()
        h.links.createGate = gate
        val o = open()
        o.vm.onAction(LinkCreateAction.Create)
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertEquals(1, h.links.created.size)
        gate.complete(Unit)
        runCurrent()
        val blank = open()
        blank.vm.onAction(LinkCreateAction.TitleChanged("   "))
        blank.vm.onAction(LinkCreateAction.Create)
        val none = open()
        none.vm.onAction(LinkCreateAction.SelectNone)
        none.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertEquals(1, h.links.created.size)
    }

    @Test
    fun offlineSaysCreatingALinkNeedsAConnection() = runTest {
        h.u18Entry()
        h.links.createFailure = NetworkError()
        val o = open()
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertEquals(LinkError.OFFLINE, o.state.error)
        assertNull(o.state.progress)
        assertNull(o.state.result)
    }

    @Test
    fun anyOtherFailureSaysNothingWasShared() = runTest {
        h.u18Entry()
        val o = open()
        for (failure in listOf(StripFailedException(), LinkInputException(LinkInputReason.NOTHING), IllegalStateException())) {
            h.links.createFailure = failure
            o.vm.onAction(LinkCreateAction.Create)
            runCurrent()
            assertEquals(failure.toString(), LinkError.FAILED, o.state.error)
            assertNull(o.state.progress)
        }
    }

    @Test
    fun aRetryClearsTheEarlierError() = runTest {
        h.u18Entry()
        h.links.createFailure = NetworkError()
        val o = open()
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        h.links.createFailure = null
        val gate = CompletableDeferred<Unit>()
        h.links.createGate = gate
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        assertNull(o.state.error)
        gate.complete(Unit)
        runCurrent()
        assertEquals(h.links.result, o.state.result)
    }

    @Test
    fun copyPutsTheUrlOnTheClipboardAndSaysCopied() = runTest {
        h.u18Entry()
        val o = open()
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        o.vm.onAction(LinkCreateAction.Copy)
        assertEquals(listOf(LINK_URL), h.sharing.copied)
        assertTrue(o.state.copied)
    }

    @Test
    fun shareHandsTheUrlToOtherApps() = runTest {
        h.u18Entry()
        val o = open()
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        o.vm.onAction(LinkCreateAction.Share)
        assertEquals(listOf(LINK_URL), h.sharing.shared)
        assertEquals(emptyList<String>(), h.sharing.copied)
    }

    @Test
    fun copyAndShareNeedALink() = runTest {
        h.u18Entry()
        val o = open()
        o.vm.onAction(LinkCreateAction.Copy)
        o.vm.onAction(LinkCreateAction.Share)
        assertEquals(emptyList<String>(), h.sharing.copied)
        assertEquals(emptyList<String>(), h.sharing.shared)
        assertFalse(o.state.copied)
    }

    @Test
    fun doneAndCancelLeaveTheScreen() = runTest {
        h.u18Entry()
        val o = open()
        o.vm.onAction(LinkCreateAction.Cancel)
        o.vm.onAction(LinkCreateAction.Create)
        runCurrent()
        o.vm.onAction(LinkCreateAction.Done)
        assertEquals(listOf<LinkCreateEvent>(LinkCreateEvent.Back, LinkCreateEvent.Back), o.events)
    }
}
