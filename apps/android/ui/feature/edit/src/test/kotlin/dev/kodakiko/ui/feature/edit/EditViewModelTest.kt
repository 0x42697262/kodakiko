@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import android.net.Uri
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
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
import java.io.File

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
    fun theNoteStaysInMemoryWhileTypingAndIsWrittenOnDone() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "L")
        advanceTimeBy(100)
        note(vm, "La")
        advanceTimeBy(100)
        note(vm, "Lake")
        assertEquals("Lake", screen.value.content.note)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals("**swim** at the lake", h.entries.meta().note)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "Lake")), h.entries.edits)
        assertEquals("Lake", h.entries.meta().note)
    }

    @Test
    fun otherChangesAreWrittenOnDone() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.RemoveTag("beach"))
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(listOf("family"), screen.value.content.tags)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf(EntryEdit(tags = listOf("family"))), h.entries.edits)
        assertEquals(listOf("family"), screen.value.content.tags)
        assertEquals(0L, currentTime)
    }

    @Test
    fun theNoteAndOtherChangesAreWrittenTogetherOnDoneAndOnlyOnce() = runTest {
        h.store(full)
        val (vm, _) = open()
        note(vm, "draft")
        vm.onAction(EditAction.RemoveTag("family"))
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "draft", tags = listOf("beach"))), h.entries.edits)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS * 2)
        runCurrent()
        h.flushes.flushAll()
        assertEquals(1, h.entries.edits.size)
    }

    @Test
    fun aSaveReadsTheStoredEntryAndAnUnchangedResultWritesNothing() = runTest {
        h.store(full)
        val (vm, _) = open()
        val readsBefore = h.entries.reads
        vm.onAction(EditAction.RemoveTag("beach"))
        vm.onAction(EditAction.ToggleTimeEditor)
        vm.onAction(EditAction.ApplyTime("2026-11-21", "09:00"))
        runCurrent()
        assertEquals(readsBefore, h.entries.reads)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertTrue(h.entries.reads > readsBefore)
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
    fun backWithChangesAsksSaveDiscardOrKeepEditingAndSaveDoesTheSameAsTheCheckMark() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "typed then back")
        vm.onAction(EditAction.Back)
        runCurrent()
        assertEquals(EditSheet.Unsaved(EditExit.BACK, isNew = false), screen.value.sheet)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(emptyList<EditEvent>(), screen.events)
        vm.onAction(EditAction.SaveChanges)
        runCurrent()
        assertNull(screen.value.sheet)
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
    fun goingToTheBackgroundStopsVoicePlaybackAndKeepsTheDraftUnsaved() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "half a thought")
        vm.onAction(EditAction.ScreenVisible(false))
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals("half a thought", screen.value.content.note)
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
    fun theCaptureButtonsOpenCaptureForThisEntryAndAddFromDeviceOpensThePicker() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "before the camera")
        vm.onAction(EditAction.Capture(CaptureMode.PHOTO))
        vm.onAction(EditAction.Capture(CaptureMode.VIDEO))
        vm.onAction(EditAction.AddFromDevice)
        runCurrent()
        assertEquals(
            listOf(
                EditEvent.Open(Route.Capture(ENTRY, CaptureMode.PHOTO)),
                EditEvent.Open(Route.Capture(ENTRY, CaptureMode.VIDEO)),
                EditEvent.PickMedia(16),
            ),
            screen.events,
        )
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals("**swim** at the lake", h.entries.meta().note)
        assertEquals("before the camera", screen.value.content.note)
    }

    @Test
    fun favoriteFlipsAtOnceAndIsWrittenOnDoneAfterTheOtherChanges() = runTest {
        h.store(full.copy(favorite = null))
        val (vm, screen) = open()
        assertFalse(screen.value.content.favorite)
        note(vm, "pending")
        vm.onAction(EditAction.ToggleFavorite)
        assertTrue(screen.value.content.favorite)
        runCurrent()
        assertEquals(emptyList<Boolean>(), h.entries.favorites)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf("edit", "setFavorite:true"), h.log.filter { it == "edit" || it.startsWith("setFavorite") })
        vm.onAction(EditAction.ToggleFavorite)
        runCurrent()
        assertFalse(screen.value.content.favorite)
        vm.onAction(EditAction.Done)
        runCurrent()
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
    fun viewEntryWithChangesAsksAndSaveReplacesTheEditPageWithTheViewer() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "seen soon")
        vm.onAction(EditAction.ViewEntry)
        runCurrent()
        assertEquals(EditSheet.Unsaved(EditExit.VIEW, isNew = false), screen.value.sheet)
        assertEquals(emptyList<EditEvent>(), screen.events)
        vm.onAction(EditAction.SaveChanges)
        runCurrent()
        assertTrue(firstEventAfter("edit"))
        assertEquals(listOf<EditEvent>(EditEvent.Replace(Route.Entry(ENTRY))), screen.events)
        assertNull(h.notices.value)
    }

    @Test
    fun aFailedSaveShowsTheErrorStaysAndTheNextSaveRetriesIt() = runTest {
        h.store(full)
        val (vm, screen) = open()
        h.entries.editFailures = 1
        vm.onAction(EditAction.RemoveTag("beach"))
        vm.onAction(EditAction.Done)
        runCurrent()
        assertTrue(screen.value.failed)
        assertEquals(emptyList<EditEvent>(), screen.events)
        assertNull(h.notices.value)
        assertEquals(listOf("beach", "family"), h.entries.meta().tags)
        assertEquals(listOf("family"), screen.value.content.tags)
        vm.onAction(EditAction.RemoveTag("family"))
        vm.onAction(EditAction.Done)
        runCurrent()
        assertFalse(screen.value.failed)
        assertEquals(listOf(EntryEdit(tags = emptyList())), h.entries.edits)
        assertEquals(emptyList<String>(), h.entries.meta().tags)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }

    @Test
    fun aFailedNoteSaveFromTheUnsavedSheetIsRetriedByTheNextSave() = runTest {
        h.store(full)
        val (vm, screen) = open()
        h.entries.editFailures = 1
        note(vm, "one")
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.SaveChanges)
        runCurrent()
        assertTrue(screen.value.failed)
        assertNull(screen.value.sheet)
        assertEquals(emptyList<EditEvent>(), screen.events)
        note(vm, "one two")
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.SaveChanges)
        runCurrent()
        assertFalse(screen.value.failed)
        assertEquals("one two", h.entries.meta().note)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }

    @Test
    fun comingBackReloadsFromStorageAndKeepsTheDraft() = runTest {
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
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
    }

    @Test
    fun anUnsavedNoteSurvivesTheReloadOnFocusAndIsWrittenOnDone() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "still typing")
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertEquals("**swim** at the lake", h.entries.meta().note)
        assertEquals("still typing", screen.value.content.note)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals("still typing", h.entries.meta().note)
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

    @Test
    fun backWithoutChangesLeavesAtOnceWithoutTheSavedNotice() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.Back)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertNull(h.notices.value)
    }

    @Test
    fun viewEntryWithoutChangesReplacesAtOnce() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.ViewEntry)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(listOf<EditEvent>(EditEvent.Replace(Route.Entry(ENTRY))), screen.events)
    }

    @Test
    fun backOnANewEntryAlwaysAsks() = runTest {
        h.store(metadata(media = listOf(photo(P1))))
        val (vm, screen) = open(isNew = true)
        vm.onAction(EditAction.Back)
        runCurrent()
        assertEquals(EditSheet.Unsaved(EditExit.BACK, isNew = true), screen.value.sheet)
        assertEquals(emptyList<EditEvent>(), screen.events)
    }

    @Test
    fun aFavoriteFlipAloneMakesThePageAsk() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleFavorite)
        vm.onAction(EditAction.Back)
        assertEquals(EditSheet.Unsaved(EditExit.BACK, isNew = false), screen.value.sheet)
    }

    @Test
    fun undoingAChangeLeavesWithoutAsking() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "changed")
        note(vm, "**swim** at the lake")
        vm.onAction(EditAction.Back)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }

    @Test
    fun keepEditingClosesTheSheetAndKeepsTheDraft() = runTest {
        h.store(full)
        val (vm, screen) = open()
        note(vm, "not yet")
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.DismissSheet)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals("not yet", screen.value.content.note)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(emptyList<EditEvent>(), screen.events)
    }

    @Test
    fun discardOnANewEntryDeletesItAndGoesHome() = runTest {
        h.store(metadata(media = listOf(photo(P1), photo(P2))))
        val (vm, screen) = open(isNew = true)
        note(vm, "never mind")
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.DiscardChanges)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(listOf(listOf(ENTRY)), h.entries.deleted)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(listOf<EditEvent>(EditEvent.Home), screen.events)
        assertEquals(listOf(listOf(P1, P2)), h.originals.forgotten)
        assertNull(h.notices.value)
    }

    @Test
    fun aFailedDiscardShowsTheErrorAndStays() = runTest {
        h.store(metadata(media = listOf(photo(P1))))
        h.entries.deleteFailure = IllegalStateException("disk full")
        val (vm, screen) = open(isNew = true)
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.DiscardChanges)
        runCurrent()
        assertTrue(screen.value.failed)
        assertEquals(emptyList<EditEvent>(), screen.events)
        assertEquals(emptyList<List<String>>(), h.originals.forgotten)
    }

    @Test
    fun discardOnAnExistingEntryRemovesOnlyTheMediaAddedInTheSessionAndKeepsTheRest() = runTest {
        h.store(full)
        h.clipboard.image = MediaInput(MediaOrigin.PASTE, File("/tmp/kodakiko-edit-test/clip.jpg"), null, null)
        val (vm, screen) = open()
        vm.onAction(EditAction.Paste)
        runCurrent()
        assertEquals(listOf(P1, VID, P2, PASTED), screen.value.content.pages.map { it.mediaId })
        note(vm, "thrown away")
        vm.onAction(EditAction.RemoveTag("beach"))
        vm.onAction(EditAction.Back)
        assertEquals(EditSheet.Unsaved(EditExit.BACK, isNew = false), screen.value.sheet)
        vm.onAction(EditAction.DiscardChanges)
        runCurrent()
        assertEquals(listOf(PASTED), h.entries.removed)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(emptyList<List<String>>(), h.entries.deleted)
        assertEquals(listOf(P1, VOX1, VID, P2), h.entries.meta().media.map { it.mediaId })
        assertEquals("**swim** at the lake", h.entries.meta().note)
        assertEquals(listOf("beach", "family"), h.entries.meta().tags)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
        assertTrue(PASTED in h.originals.forgotten.single())
    }

    @Test
    fun anAddedItemAloneMakesThePageAskAndDiscardFromViewEntryOpensTheViewer() = runTest {
        h.store(full)
        h.clipboard.image = MediaInput(MediaOrigin.PASTE, File("/tmp/kodakiko-edit-test/clip.jpg"), null, null)
        val (vm, screen) = open()
        vm.onAction(EditAction.Paste)
        runCurrent()
        vm.onAction(EditAction.ViewEntry)
        assertEquals(EditSheet.Unsaved(EditExit.VIEW, isNew = false), screen.value.sheet)
        vm.onAction(EditAction.DiscardChanges)
        runCurrent()
        assertEquals(listOf(PASTED), h.entries.removed)
        assertEquals(listOf<EditEvent>(EditEvent.Replace(Route.Entry(ENTRY))), screen.events)
    }

    @Test
    fun savedMediaIsNoLongerRemovedByALaterDiscard() = runTest {
        h.store(full)
        h.clipboard.image = MediaInput(MediaOrigin.PASTE, File("/tmp/kodakiko-edit-test/clip.jpg"), null, null)
        val (vm, _) = open()
        vm.onAction(EditAction.Paste)
        runCurrent()
        h.flushes.flushAll()
        note(vm, "after the lock")
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.DiscardChanges)
        runCurrent()
        assertEquals(emptyList<String>(), h.entries.removed)
        assertEquals(listOf(P1, VOX1, VID, P2, PASTED), h.entries.meta().media.map { it.mediaId })
    }

    @Test
    fun pickedFilesAreCopiedImportedReportedAndAddedAsPages() = runTest {
        h.store(full)
        val first = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/41")
        val broken = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/42")
        val input = inputFile("picked.jpg")
        h.picks.inputs[first] = input
        h.picks.inputs[broken] = null
        val (vm, screen) = open()
        vm.onAction(EditAction.Picked(emptyList()))
        runCurrent()
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        vm.onAction(EditAction.Picked(listOf(first, broken)))
        runCurrent()
        assertEquals(listOf("copy:$first", "importOne", "addMedia:$PASTED", "copy:$broken"), h.log.filter { it.startsWith("copy:") || it == "importOne" || it.startsWith("addMedia") })
        assertEquals(listOf(input to PASTED), h.picks.imported)
        assertEquals(listOf(P1, VID, P2, PASTED), screen.value.content.pages.map { it.mediaId })
        assertTrue(screen.value.failed)
    }

    @Test
    fun aRefusedPickIsReportedWithoutAMediaId() = runTest {
        h.store(full)
        val uri = Uri.parse("content://com.android.providers.media.documents/document/image%3A7")
        val input = inputFile("refused.jpg")
        h.picks.inputs[uri] = input
        h.imports.refuse = ImportRefusal.TRAY_FULL
        val (vm, screen) = open()
        vm.onAction(EditAction.Picked(listOf(uri)))
        runCurrent()
        assertEquals(listOf<Pair<MediaInput, String?>>(input to null), h.picks.imported)
        assertTrue(screen.value.failed)
        assertEquals(emptyList<List<String>>(), h.entries.added)
    }

    @Test
    fun addFromDeviceAsksForTheSlotsLeft() = runTest {
        h.store(full)
        h.config.config = publicConfig(maxMedia = 6)
        val (vm, screen) = open()
        vm.onAction(EditAction.AddFromDevice)
        h.config.config = null
        vm.onAction(EditAction.AddFromDevice)
        runCurrent()
        assertEquals(listOf<EditEvent>(EditEvent.PickMedia(2), EditEvent.PickMedia(16)), screen.events)
    }

    @Test
    fun savingAnEntryWithGalleryImportsOffersToDeleteTheOriginalsAndKeepLeaves() = runTest {
        h.store(full.copy(media = full.media + photo(P3)))
        h.originals.pending += P3
        val (vm, screen) = open()
        note(vm, "imported")
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(EditSheet.DeviceOriginals(1, listOf(P1, VOX1, VID, P2, P3), EditExit.BACK, notice = true), screen.value.sheet)
        assertEquals("imported", h.entries.meta().note)
        assertEquals(emptyList<EditEvent>(), screen.events)
        assertNull(h.notices.value)
        vm.onAction(EditAction.KeepDeviceOriginals)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(listOf(listOf(P1, VOX1, VID, P2, P3)), h.originals.kept)
        assertEquals(emptyList<List<String>>(), h.originals.deletedOriginals)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
        assertEquals(GalleryNotice.Saved(NOW_MS), h.notices.value)
    }

    @Test
    fun dismissingTheDeviceOriginalsSheetKeepsThem() = runTest {
        h.store(full)
        h.originals.pending += P2
        val (vm, screen) = open()
        vm.onAction(EditAction.Done)
        runCurrent()
        vm.onAction(EditAction.DismissSheet)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(1, h.originals.kept.size)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }

    @Test
    fun deleteFromDeviceDeletesTheOriginalsThenLeaves() = runTest {
        h.store(full)
        h.originals.pending += P2
        val (vm, screen) = open(isNew = true)
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.SaveChanges)
        runCurrent()
        assertEquals(EditSheet.DeviceOriginals(1, listOf(P1, VOX1, VID, P2), EditExit.BACK, notice = true), screen.value.sheet)
        vm.onAction(EditAction.DeleteDeviceOriginals)
        runCurrent()
        assertEquals(listOf(listOf(P2)), h.originals.deletedOriginals)
        assertEquals(listOf("deleteOriginals", "event:${EditEvent.Home}"), h.log.filter { it == "deleteOriginals" || it.startsWith("event:") })
        assertEquals(GalleryNotice.Saved(NOW_MS), h.notices.value)
    }

    @Test
    fun savingForTheViewerOffersTheOriginalsWithoutTheSavedNotice() = runTest {
        h.store(full)
        h.originals.pending += P1
        val (vm, screen) = open()
        note(vm, "then view")
        vm.onAction(EditAction.ViewEntry)
        vm.onAction(EditAction.SaveChanges)
        runCurrent()
        assertEquals(EditSheet.DeviceOriginals(1, listOf(P1, VOX1, VID, P2), EditExit.VIEW, notice = false), screen.value.sheet)
        vm.onAction(EditAction.KeepDeviceOriginals)
        runCurrent()
        assertEquals(listOf<EditEvent>(EditEvent.Replace(Route.Entry(ENTRY))), screen.events)
        assertNull(h.notices.value)
    }

    @Test
    fun withoutDeletableOriginalsSavingLeavesAtOnce() = runTest {
        h.store(full)
        val (vm, screen) = open()
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf(listOf(P1, VOX1, VID, P2)), h.originals.counted)
        assertNull(screen.value.sheet)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }

    @Test
    fun discardForgetsThePendingOriginalsOfTheEntry() = runTest {
        h.store(full)
        h.originals.pending += P2
        val (vm, _) = open()
        note(vm, "no")
        vm.onAction(EditAction.Back)
        vm.onAction(EditAction.DiscardChanges)
        runCurrent()
        assertEquals(listOf(listOf(P1, VOX1, VID, P2)), h.originals.forgotten)
        assertEquals(emptySet<String>(), h.originals.pending)
    }
}
