@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = EditHarness()

    private val mixed = metadata(media = listOf(photo(P1, caption = "Heron at dawn"), voice(VOX1), video(VID), photo(P2)))

    private fun TestScope.open(meta: EntryMetadataV1 = mixed): Pair<EditViewModel, Observed<EditState, EditEvent>> {
        h.store(meta)
        val vm = EditViewModel(h.deps, ENTRY, false)
        clearAtEnd(vm)
        return vm to observe(vm.state, vm.events, h.log)
    }

    private val EditState.content: EditBody get() = requireNotNull(body)

    private fun TestScope.save(vm: EditViewModel) {
        vm.onAction(EditAction.Done)
        runCurrent()
    }

    @Test
    fun swipingOrTappingADotChangesThePage() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.PageChanged(2))
        assertEquals(2, screen.value.page)
        vm.onAction(EditAction.PageChanged(0))
        assertEquals(0, screen.value.page)
    }

    @Test
    fun theMenuOffersSetAsCoverExceptOnTheFirstItem() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.OpenMediaMenu(P1))
        assertEquals(EditSheet.MediaMenu(P1, MediaKind.PHOTO, canSetCover = false), screen.value.sheet)
        vm.onAction(EditAction.OpenMediaMenu(VID))
        assertEquals(EditSheet.MediaMenu(VID, MediaKind.VIDEO, canSetCover = true), screen.value.sheet)
        vm.onAction(EditAction.DismissSheet)
        assertNull(screen.value.sheet)
    }

    @Test
    fun aPhotoBehindAVoiceNoteCanStillBecomeTheCover() = runTest {
        val (vm, screen) = open(metadata(media = listOf(voice(VOX1), photo(P1))))
        vm.onAction(EditAction.OpenMediaMenu(P1))
        assertEquals(EditSheet.MediaMenu(P1, MediaKind.PHOTO, canSetCover = true), screen.value.sheet)
        vm.onAction(EditAction.SetCover(P1))
        save(vm)
        assertEquals(listOf(EntryEdit(mediaOrder = listOf(P1, VOX1))), h.entries.edits)
    }

    @Test
    fun setAsCoverMovesTheItemFirstAndWritesTheOrderOnSave() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.PageChanged(1))
        vm.onAction(EditAction.OpenMediaMenu(VID))
        vm.onAction(EditAction.SetCover(VID))
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(0, screen.value.page)
        assertEquals(listOf(VID, P1, P2), screen.value.content.pages.map { it.mediaId })
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        save(vm)
        assertEquals(listOf(EntryEdit(mediaOrder = listOf(VID, P1, VOX1, P2))), h.entries.edits)
        assertEquals(listOf(VID, P1, VOX1, P2), h.entries.meta().media.map { it.mediaId })
    }

    @Test
    fun theCaptionSheetStartsWithTheCurrentCaption() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.EditCaption(P1))
        assertEquals(EditSheet.Caption(P1, "Heron at dawn"), screen.value.sheet)
        vm.onAction(EditAction.EditCaption(P2))
        assertEquals(EditSheet.Caption(P2, ""), screen.value.sheet)
    }

    @Test
    fun aCaptionIsTrimmedShownOnItsPageAndWrittenOnSave() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.EditCaption(P2))
        vm.onAction(EditAction.SaveCaption(P2, "  Sunset over the bay  "))
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals("Sunset over the bay", screen.value.content.pages.single { it.mediaId == P2 }.caption)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        save(vm)
        assertEquals(listOf(EntryEdit(captions = mapOf(P2 to "Sunset over the bay"))), h.entries.edits)
        assertEquals("Sunset over the bay", h.entries.meta().media.single { it.mediaId == P2 }.caption)
    }

    @Test
    fun anEmptyCaptionRemovesIt() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.SaveCaption(P1, "   "))
        runCurrent()
        assertNull(screen.value.content.pages.first().caption)
        save(vm)
        assertEquals(listOf(EntryEdit(captions = mapOf(P1 to ""))), h.entries.edits)
        assertNull(h.entries.meta().media.first().caption)
    }

    @Test
    fun aCaptionHoldsOneTo64Characters() = runTest {
        val (vm, _) = open()
        vm.onAction(EditAction.SaveCaption(P2, "x"))
        save(vm)
        assertEquals("x", h.entries.meta().media.single { it.mediaId == P2 }.caption)
        vm.onAction(EditAction.SaveCaption(P2, "y".repeat(64)))
        save(vm)
        assertEquals("y".repeat(64), h.entries.meta().media.single { it.mediaId == P2 }.caption)
        vm.onAction(EditAction.SaveCaption(P2, "z".repeat(70)))
        save(vm)
        assertEquals("z".repeat(64), h.entries.meta().media.single { it.mediaId == P2 }.caption)
    }

    @Test
    fun shareAndSaveToDeviceSendThatItem() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.OpenMediaMenu(P2))
        vm.onAction(EditAction.ShareItem(P2))
        runCurrent()
        assertNull(screen.value.sheet)
        vm.onAction(EditAction.OpenMediaMenu(VID))
        vm.onAction(EditAction.SaveToDevice(VID))
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(listOf("shareItem:$P2", "saveToDevice:$VID"), h.log.filter { it.startsWith("share") || it.startsWith("save") })
    }

    @Test
    fun cropOpensTheCropScreenForThatPhotoAndTheDraftFollowsTheCroppedCopy() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.NoteChanged("before crop", TextSelection(11, 11)))
        vm.onAction(EditAction.SaveCaption(P2, "cropped soon"))
        vm.onAction(EditAction.OpenMediaMenu(P2))
        vm.onAction(EditAction.Crop(P2))
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals("", h.entries.meta().note)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(listOf<EditEvent>(EditEvent.Open(Route.EntryCrop(ENTRY, P2))), screen.events)
        h.entries.stored[ENTRY] = h.entries.meta().copy(media = h.entries.meta().media.map { if (it.mediaId == P2) photo(CROPPED, 3000, 2000) else it })
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertEquals(listOf(P1, VID, CROPPED), screen.value.content.pages.map { it.mediaId })
        assertEquals("cropped soon", screen.value.content.pages.last().caption)
        assertEquals("before crop", screen.value.content.note)
        assertEquals(listOf(P2 to CROPPED), h.originals.replacements)
        save(vm)
        assertEquals("before crop", h.entries.meta().note)
        assertEquals("cropped soon", h.entries.meta().media.single { it.mediaId == CROPPED }.caption)
    }

    @Test
    fun removeAsksFirstThenRemovesTheItemFromThePageAndWritesItOnSave() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.PageChanged(2))
        vm.onAction(EditAction.AskRemove(P2))
        assertEquals(EditSheet.Remove(P2, MediaKind.PHOTO, last = false), screen.value.sheet)
        assertEquals(emptyList<String>(), h.entries.removed)
        vm.onAction(EditAction.Remove(P2))
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals(0, screen.value.page)
        assertEquals(listOf(P1, VID), screen.value.content.pages.map { it.mediaId })
        assertEquals(3, screen.value.content.itemCount)
        assertEquals(emptyList<String>(), h.entries.removed)
        save(vm)
        assertEquals(listOf(P2), h.entries.removed)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
    }

    @Test
    fun removalsAreWrittenBeforeTheOtherChanges() = runTest {
        val (vm, _) = open()
        vm.onAction(EditAction.NoteChanged("pending", TextSelection(7, 7)))
        vm.onAction(EditAction.Remove(VID))
        save(vm)
        assertEquals(listOf("removeMedia:$VID", "edit"), h.log.filter { it == "edit" || it.startsWith("removeMedia") })
        assertEquals(listOf(EntryEdit(note = "pending")), h.entries.edits)
    }

    @Test
    fun theRemoveSheetNamesTheKind() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.AskRemove(VID))
        assertEquals(EditSheet.Remove(VID, MediaKind.VIDEO, last = false), screen.value.sheet)
    }

    @Test
    fun theLastItemCannotBeRemoved() = runTest {
        val (vm, screen) = open(metadata(media = listOf(photo(P1))))
        vm.onAction(EditAction.AskRemove(P1))
        assertEquals(EditSheet.Remove(P1, MediaKind.PHOTO, last = true), screen.value.sheet)
        vm.onAction(EditAction.Remove(P1))
        runCurrent()
        save(vm)
        assertEquals(emptyList<String>(), h.entries.removed)
        assertEquals(listOf(P1), screen.value.content.pages.map { it.mediaId })
        assertEquals(listOf(P1), h.entries.meta().media.map { it.mediaId })
    }

    @Test
    fun aVoiceNoteCountsAsAnItemForTheLastItemRule() = runTest {
        val (vm, screen) = open(metadata(media = listOf(photo(P1), voice(VOX1))))
        vm.onAction(EditAction.AskRemove(P1))
        assertEquals(EditSheet.Remove(P1, MediaKind.PHOTO, last = false), screen.value.sheet)
        vm.onAction(EditAction.Remove(P1))
        runCurrent()
        assertEquals(emptyList<EditPage>(), screen.value.content.pages)
        assertEquals(listOf(EditVoiceNote(VOX1, 20_000)), screen.value.content.voiceNotes)
        save(vm)
        assertEquals(listOf(P1), h.entries.removed)
    }

    @Test
    fun tappingAVideoOpensTheFullPlayer() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.OpenPlayer(VID))
        runCurrent()
        assertEquals(listOf<EditEvent>(EditEvent.Open(Route.Play(ENTRY, VID))), screen.events)
    }
}
