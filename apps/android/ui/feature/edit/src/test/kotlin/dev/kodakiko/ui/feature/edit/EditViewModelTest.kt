@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.contract.MediaKind
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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
class EditViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = EditHarness()

    private val full = metadata(
        media = listOf(photo(P1, caption = "Heron at dawn"), voice(VOX1), video(VID), photo(P2)),
        note = "**swim** at the lake",
        tags = listOf("beach", "family"),
        location = PARIS,
        favorite = true,
    )

    private fun TestScope.open(isNew: Boolean = false): Pair<EditViewModel, Observed<EditState, EditEvent>> {
        val vm = EditViewModel(h.deps, ENTRY, isNew)
        clearAtEnd(vm)
        return vm to observe(vm.state, vm.events, h.log)
    }

    private val EditState.content: EditBody get() = requireNotNull(body)

    private fun note(vm: EditViewModel, text: String) = vm.onAction(EditAction.NoteChanged(text, TextSelection(text.length, text.length)))

    private fun firstEventAfter(entry: String): Boolean {
        val write = h.log.indexOf(entry)
        val event = h.log.indexOfFirst { it.startsWith("event:") }
        return write >= 0 && event > write
    }

    @Test
    fun loadsTheEntryIntoThePageLayout() = runTest {
        h.store(full)
        val (_, screen) = open()
        val state = screen.value
        assertEquals(ENTRY, state.entryId)
        assertFalse(state.isNew)
        assertEquals(
            listOf(
                EditPage(P1, MediaKind.PHOTO, 4000, 3000, "Heron at dawn"),
                EditPage(VID, MediaKind.VIDEO, 1920, 1080, null),
                EditPage(P2, MediaKind.PHOTO, 4000, 3000, null),
            ),
            state.content.pages,
        )
        assertEquals(listOf(EditVoiceNote(VOX1, 20_000)), state.content.voiceNotes)
        assertEquals(4, state.content.itemCount)
        assertEquals(EditTime("2026-11-21T08:00:00.000Z", 60, NOW_UTC, DEVICE_OFFSET), state.content.time)
        assertEquals(PARIS, state.content.location)
        assertEquals(listOf("beach", "family"), state.content.tags)
        assertEquals("**swim** at the lake", state.content.note)
        assertTrue(state.content.favorite)
        assertEquals(0, state.page)
        assertNull(state.sheet)
        assertNull(state.noteEditor)
        assertNull(state.timeEditor)
        assertNull(state.recording)
        assertFalse(state.failed)
        assertFalse(state.voiceFailed)
        assertFalse(state.canPaste)
        assertEquals(emptyList<EditEvent>(), screen.events)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
    }

    @Test
    fun aNewEntryIsMarkedNew() = runTest {
        h.store(metadata(media = listOf(photo(P1))))
        val (_, screen) = open(isNew = true)
        assertTrue(screen.value.isNew)
        assertEquals(1, screen.value.content.pages.size)
    }

    @Test
    fun anEntryThatCannotBeReadLeavesTheEditPage() = runTest {
        h.entries.readFailure = IllegalStateException("damaged")
        val (_, screen) = open()
        assertNull(screen.value.body)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
        assertNull(h.notices.value)
    }

    @Test
    fun theNoteIsWritten600MillisecondsAfterTypingStops() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "L")
        advanceTimeBy(100)
        note(vm, "La")
        advanceTimeBy(100)
        note(vm, "Lake")
        assertEquals("Lake", screen.value.content.note)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "Lake")), h.entries.edits)
        assertEquals("Lake", h.entries.meta().note)
    }

    @Test
    fun otherChangesAreWrittenAtOnce() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.RemoveTag("beach"))
        runCurrent()
        assertEquals(listOf(EntryEdit(tags = listOf("family"))), h.entries.edits)
        assertEquals(listOf("family"), screen.value.content.tags)
        assertEquals(0L, currentTime)
    }

    @Test
    fun aPendingNoteIsWrittenWithTheNextImmediateChangeAndOnlyOnce() = runTest {
        h.store(full)
        val (vm, _) = open()
        note(vm, "draft")
        vm.onAction(EditAction.RemoveTag("family"))
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "draft", tags = listOf("beach"))), h.entries.edits)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(1, h.entries.edits.size)
    }

    @Test
    fun everyWriteReadsTheStoredEntryAndAnUnchangedResultWritesNothing() = runTest {
        h.store(full)
        val (vm, _) = open()
        val readsBefore = h.entries.reads
        vm.onAction(EditAction.RemoveTag("beach"))
        runCurrent()
        assertTrue(h.entries.reads > readsBefore)
        vm.onAction(EditAction.ToggleTimeEditor)
        vm.onAction(EditAction.ApplyTime("2026-11-21", "09:00"))
        runCurrent()
        assertEquals(listOf(EntryEdit(tags = listOf("family"))), h.entries.edits)
        assertEquals(listOf("edit"), h.log.filter { it == "edit" })
    }

    @Test
    fun theCheckMarkFlushesLeavesAndShowsSaved() = runTest {
        h.store(full)
        val (vm, screen) = open()
        h.clock.nowMs = NOW_MS + 5_000
        note(vm, "Lake day **swim**")
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "Lake day **swim**")), h.entries.edits)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
        assertTrue(firstEventAfter("edit"))
        assertEquals(GalleryNotice.Saved(NOW_MS + 5_000), h.notices.value)
        assertEquals(0L, currentTime)
    }

    @Test
    fun backDoesTheSameAsTheCheckMark() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "typed then back")
        vm.onAction(EditAction.Back)
        runCurrent()
        assertEquals("typed then back", h.entries.meta().note)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
        assertEquals(GalleryNotice.Saved(NOW_MS), h.notices.value)
    }

    @Test
    fun leavingANewEntryGoesHome() = runTest {
        h.store(metadata(media = listOf(photo(P1))))
        val (vm, screen) = open(isNew = true)
        note(vm, "first words")
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals("first words", h.entries.meta().note)
        assertEquals(listOf<EditEvent>(EditEvent.Home), screen.events)
        assertEquals(GalleryNotice.Saved(NOW_MS), h.notices.value)
    }

    @Test
    fun leavingWithoutChangesWritesNothing() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }

    @Test
    fun goingToTheBackgroundFlushesAndStopsVoicePlayback() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "half a thought")
        vm.onAction(EditAction.ScreenVisible(false))
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "half a thought")), h.entries.edits)
        assertTrue("voiceStop" in h.log)
        assertEquals(emptyList<EditEvent>(), screen.events)
        assertNull(h.notices.value)
        assertEquals(0L, currentTime)
    }

    @Test
    fun lockingFlushesEveryOpenEditPage() = runTest {
        h.store(full)
        h.store(metadata(media = listOf(photo(P3))), "entry-2")
        val (first, _) = open()
        val second = EditViewModel(h.deps, "entry-2", false)
        clearAtEnd(second)
        observe(second.state, second.events)
        note(first, "first page")
        second.onAction(EditAction.NoteChanged("second page", TextSelection(11, 11)))
        h.flushes.flushAll()
        assertEquals("first page", h.entries.meta().note)
        assertEquals("second page", h.entries.meta("entry-2").note)
        assertEquals(0L, currentTime)
    }

    @Test
    fun lockingWaitsForAWriteInFlight() = runTest {
        h.store(full)
        val (vm, _) = open()
        val gate = CompletableDeferred<Unit>()
        h.entries.editGate = gate
        vm.onAction(EditAction.RemoveTag("beach"))
        runCurrent()
        val flushed = async { h.flushes.flushAll() }
        runCurrent()
        assertFalse(flushed.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(flushed.isCompleted)
        assertEquals(listOf("family"), h.entries.meta().tags)
    }

    @Test
    fun aClearedPageIsNoLongerFlushedOnLock() = runTest {
        h.store(full)
        val (vm, _) = open()
        clear(vm)
        h.flushes.flushAll()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
    }

    @Test
    fun theCaptureButtonsFlushThenOpenCaptureForThisEntry() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "before the camera")
        vm.onAction(EditAction.Capture(CaptureMode.PHOTO))
        runCurrent()
        assertTrue(firstEventAfter("edit"))
        vm.onAction(EditAction.Capture(CaptureMode.VIDEO))
        vm.onAction(EditAction.AddFromDevice)
        runCurrent()
        assertEquals(
            listOf<EditEvent>(
                EditEvent.Open(Route.Capture(ENTRY, CaptureMode.PHOTO)),
                EditEvent.Open(Route.Capture(ENTRY, CaptureMode.VIDEO)),
                EditEvent.Open(Route.Capture(ENTRY, CaptureMode.PHOTO)),
            ),
            screen.events,
        )
        assertEquals("before the camera", h.entries.meta().note)
    }

    @Test
    fun favoriteFlipsAtOnceAndIsWrittenAfterTheFlush() = runTest {
        h.store(full.copy(favorite = null))
        val (vm, screen) = open()
        assertFalse(screen.value.content.favorite)
        note(vm, "pending")
        vm.onAction(EditAction.ToggleFavorite)
        assertTrue(screen.value.content.favorite)
        runCurrent()
        assertEquals(listOf("edit", "setFavorite:true"), h.log.filter { it == "edit" || it.startsWith("setFavorite") })
        vm.onAction(EditAction.ToggleFavorite)
        runCurrent()
        assertFalse(screen.value.content.favorite)
        assertEquals(listOf(true, false), h.entries.favorites)
    }

    @Test
    fun moveToTrashFlushesTrashesAndGoesHomeWithTheTrashedNotice() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "last words")
        vm.onAction(EditAction.Trash)
        runCurrent()
        assertEquals(listOf("edit", "trash", "event:${EditEvent.Home}"), h.log.filter { it == "edit" || it == "trash" || it.startsWith("event:") })
        assertEquals(listOf(listOf(ENTRY)), h.entries.trashed)
        assertEquals(GalleryNotice.Trashed(ENTRY), h.notices.value)
        assertEquals(listOf<EditEvent>(EditEvent.Home), screen.events)
    }

    @Test
    fun viewEntryFlushesAndReplacesTheEditPageWithTheViewer() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "seen soon")
        vm.onAction(EditAction.ViewEntry)
        runCurrent()
        assertTrue(firstEventAfter("edit"))
        assertEquals(listOf<EditEvent>(EditEvent.Replace(Route.Entry(ENTRY))), screen.events)
        assertNull(h.notices.value)
    }

    @Test
    fun aFailedWriteShowsTheErrorAndTheNextChangeRetriesIt() = runTest {
        h.store(full)
        val (vm, screen) = open()
        h.entries.editFailures = 1
        vm.onAction(EditAction.RemoveTag("beach"))
        runCurrent()
        assertTrue(screen.value.failed)
        assertEquals(listOf("beach", "family"), h.entries.meta().tags)
        assertEquals(listOf("family"), screen.value.content.tags)
        vm.onAction(EditAction.RemoveTag("family"))
        runCurrent()
        assertFalse(screen.value.failed)
        assertEquals(listOf(EntryEdit(tags = emptyList())), h.entries.edits)
        assertEquals(emptyList<String>(), h.entries.meta().tags)
    }

    @Test
    fun aFailedNoteWriteIsRetriedByTheNextTypedWord() = runTest {
        h.store(full)
        val (vm, screen) = open()
        h.entries.editFailures = 1
        note(vm, "one")
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS)
        runCurrent()
        assertTrue(screen.value.failed)
        note(vm, "one two")
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS)
        runCurrent()
        assertFalse(screen.value.failed)
        assertEquals("one two", h.entries.meta().note)
    }

    @Test
    fun comingBackFlushesThenReloadsFromStorage() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "typed before the camera")
        vm.onAction(EditAction.ScreenVisible(false))
        runCurrent()
        h.entries.stored[ENTRY] = h.entries.meta().copy(media = h.entries.meta().media + photo(P3))
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertEquals(listOf(P1, VID, P2, P3), screen.value.content.pages.map { it.mediaId })
        assertEquals(5, screen.value.content.itemCount)
        assertEquals("typed before the camera", screen.value.content.note)
    }

    @Test
    fun aPendingNoteSurvivesTheReloadOnFocus() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "still typing")
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertEquals("still typing", h.entries.meta().note)
        assertEquals("still typing", screen.value.content.note)
    }

    @Test
    fun shareOffersInKodakikoOnlyWhenTheServerAllowsSharingOrLinks() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.Share)
        h.config.config = publicConfig(sharing = false, links = true)
        vm.onAction(EditAction.Share)
        h.config.config = publicConfig(sharing = false, links = false)
        vm.onAction(EditAction.Share)
        h.config.config = null
        vm.onAction(EditAction.Share)
        runCurrent()
        assertEquals(
            listOf<EditEvent>(
                EditEvent.Share(listOf(ENTRY), true),
                EditEvent.Share(listOf(ENTRY), true),
                EditEvent.Share(listOf(ENTRY), false),
                EditEvent.Share(listOf(ENTRY), false),
            ),
            screen.events,
        )
    }
}
