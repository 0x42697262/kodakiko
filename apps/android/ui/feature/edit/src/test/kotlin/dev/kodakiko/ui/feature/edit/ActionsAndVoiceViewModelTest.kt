@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.entries.VoiceNoteInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class ActionsAndVoiceViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = EditHarness()

    private val mixed = metadata(media = listOf(photo(P1), voice(VOX1), video(VID), photo(P2)))

    private val clip = MediaInput(MediaOrigin.PASTE, File("/tmp/kodakiko-edit-test/clip.jpg"), null, null)

    private fun TestScope.open(meta: EntryMetadataV1 = mixed): Pair<EditViewModel, Observed<EditState, EditEvent>> {
        h.store(meta)
        val vm = EditViewModel(h.deps, ENTRY, false)
        clearAtEnd(vm)
        return vm to observe(vm.state, vm.events, h.log)
    }

    private val EditState.content: EditBody get() = requireNotNull(body)

    private fun writes(): List<String> = h.log.filter { it == "edit" || it.startsWith("import") || it.startsWith("addMedia") || it.startsWith("record") || it == "readClipboard" }

    @Test
    fun theVoiceTimerTicksEveryQuarterSecondAndStopsAtTenMinutes() {
        assertEquals(250L, EditConstants.VOICE_TICK_MS)
        assertEquals(600_000L, EditConstants.VOICE_MAX_MS)
    }

    @Test
    fun pasteShowsOnlyWhileTheClipboardHoldsAnImage() = runTest {
        val (vm, screen) = open()
        assertFalse(screen.value.canPaste)
        h.clipboard.image = clip
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertTrue(screen.value.canPaste)
        h.clipboard.image = null
        vm.onAction(EditAction.ScreenVisible(false))
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertFalse(screen.value.canPaste)
    }

    @Test
    fun theClipboardIsCheckedWhenThePageOpens() = runTest {
        h.clipboard.image = clip
        val (_, screen) = open()
        runCurrent()
        assertTrue(screen.value.canPaste)
        assertEquals(emptyList<String>(), h.log.filter { it == "readClipboard" })
    }

    @Test
    fun pasteReadsTheClipboardThenImportsTheImageAsANewPageAndKeepsTheDraft() = runTest {
        h.clipboard.image = clip
        val (vm, screen) = open()
        vm.onAction(EditAction.NoteChanged("pasting", TextSelection(7, 7)))
        vm.onAction(EditAction.Paste)
        runCurrent()
        assertEquals(listOf("readClipboard", "importOne", "addMedia:$PASTED"), writes())
        assertEquals("", h.entries.meta().note)
        assertEquals(ImportCall(clip, ImportSlots(used = 4, limit = 20)), h.imports.calls.single())
        assertEquals(listOf(P1, VID, P2, PASTED), screen.value.content.pages.map { it.mediaId })
        assertEquals(5, screen.value.content.itemCount)
        assertEquals("pasting", screen.value.content.note)
        assertFalse(screen.value.failed)
    }

    @Test
    fun theImportLimitComesFromTheServerConfigWithTheV031Fallback() = runTest {
        h.clipboard.image = clip
        h.config.config = publicConfig(maxMedia = 12)
        val (vm, _) = open()
        vm.onAction(EditAction.Paste)
        runCurrent()
        h.config.config = null
        h.imports.next = photo(P3)
        vm.onAction(EditAction.Paste)
        runCurrent()
        assertEquals(listOf(ImportSlots(4, 12), ImportSlots(5, 20)), h.imports.calls.map { it.slots })
    }

    @Test
    fun aRefusedPasteShowsTheErrorAndAddsNothing() = runTest {
        h.clipboard.image = clip
        h.imports.refuse = ImportRefusal.TRAY_FULL
        val (vm, screen) = open()
        vm.onAction(EditAction.Paste)
        runCurrent()
        assertTrue(screen.value.failed)
        assertEquals(emptyList<List<String>>(), h.entries.added)
        assertEquals(3, screen.value.content.pages.size)
    }

    @Test
    fun anEmptiedClipboardPastesNothing() = runTest {
        h.clipboard.image = clip
        val (vm, screen) = open()
        h.clipboard.image = null
        vm.onAction(EditAction.Paste)
        runCurrent()
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertFalse(screen.value.failed)
    }

    @Test
    fun recordingShowsATileWithTheElapsedTime() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        assertTrue(h.recorder.recording)
        assertEquals(RecordingUi(0), screen.value.recording)
        h.recorder.elapsed = 1_300
        advanceTimeBy(EditConstants.VOICE_TICK_MS)
        runCurrent()
        assertEquals(RecordingUi(1_300), screen.value.recording)
        h.recorder.elapsed = 2_050
        advanceTimeBy(EditConstants.VOICE_TICK_MS)
        runCurrent()
        assertEquals(RecordingUi(2_050), screen.value.recording)
        vm.onAction(EditAction.StopRecording)
        runCurrent()
        assertNull(screen.value.recording)
    }

    @Test
    fun stoppingImportsTheRecordingAsAVoiceNote() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        vm.onAction(EditAction.NoteChanged("talking", TextSelection(7, 7)))
        vm.onAction(EditAction.StopRecording)
        runCurrent()
        assertNull(screen.value.recording)
        assertEquals(listOf("recordStart", "recordStop", "importVoiceNote", "addMedia:$RECORDED"), writes())
        assertEquals("talking", screen.value.content.note)
        assertEquals("", h.entries.meta().note)
        val call = h.imports.calls.single()
        assertEquals(requireNotNull(h.recorder.result).input, call.input)
        assertEquals(VoiceNoteInfo(2_400, "audio/mp4"), call.voice)
        assertEquals(ImportSlots(used = 4, limit = 20), call.slots)
        assertEquals(listOf(EditVoiceNote(VOX1, 20_000), EditVoiceNote(RECORDED, 2_400)), screen.value.content.voiceNotes)
        assertEquals(listOf(P1, VID, P2), screen.value.content.pages.map { it.mediaId })
        assertFalse(screen.value.voiceFailed)
    }

    @Test
    fun theMicrophoneButtonAlsoStopsARecording() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        assertNull(screen.value.recording)
        assertEquals(listOf(RECORDED), h.entries.added.flatten())
    }

    @Test
    fun aRecordingStopsByItselfAfterTenMinutes() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        h.recorder.elapsed = EditConstants.VOICE_MAX_MS - 1
        advanceTimeBy(EditConstants.VOICE_TICK_MS)
        runCurrent()
        assertTrue(h.recorder.recording)
        h.recorder.elapsed = EditConstants.VOICE_MAX_MS
        advanceTimeBy(EditConstants.VOICE_TICK_MS)
        runCurrent()
        assertFalse(h.recorder.recording)
        assertNull(screen.value.recording)
        assertEquals(listOf(RECORDED), h.entries.added.flatten())
        advanceTimeBy(EditConstants.VOICE_TICK_MS * 4)
        runCurrent()
        assertEquals(1, h.log.count { it == "recordStop" })
    }

    @Test
    fun aMicrophoneProblemShowsTheVoiceErrorUntilTheNextTry() = runTest {
        h.recorder.startFailure = SecurityException("RECORD_AUDIO is not granted")
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        assertTrue(screen.value.voiceFailed)
        assertNull(screen.value.recording)
        h.recorder.startFailure = null
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        assertFalse(screen.value.voiceFailed)
        assertEquals(RecordingUi(0), screen.value.recording)
        vm.onAction(EditAction.StopRecording)
        runCurrent()
    }

    @Test
    fun aRefusedVoiceNoteShowsTheVoiceError() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        h.imports.refuse = ImportRefusal.TRAY_FULL
        vm.onAction(EditAction.StopRecording)
        runCurrent()
        assertTrue(screen.value.voiceFailed)
        assertNull(screen.value.recording)
        assertEquals(emptyList<List<String>>(), h.entries.added)
    }

    @Test
    fun aRecordingThatProducedNoFileAddsNothing() = runTest {
        h.recorder.result = null
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleRecording)
        runCurrent()
        vm.onAction(EditAction.StopRecording)
        runCurrent()
        assertNull(screen.value.recording)
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertFalse(screen.value.voiceFailed)
    }

    @Test
    fun aVoiceNotePlaysAndPausesThroughThePlayer() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleVoice(VOX1))
        runCurrent()
        assertEquals(VOX1, screen.value.voicePlaying)
        vm.onAction(EditAction.ToggleVoice(VOX1))
        runCurrent()
        assertNull(screen.value.voicePlaying)
        assertEquals(listOf(VOX1, VOX1), h.voice.toggles)
    }

    @Test
    fun aVoiceNoteIsRemovedWithoutAskingAndWrittenOnSave() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.RemoveVoice(VOX1))
        runCurrent()
        assertEquals(emptyList<EditVoiceNote>(), screen.value.content.voiceNotes)
        assertEquals(emptyList<String>(), h.entries.removed)
        assertNull(screen.value.sheet)
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals(listOf(VOX1), h.entries.removed)
    }

    @Test
    fun theOnlyItemCannotBeRemovedEvenWhenItIsAVoiceNote() = runTest {
        val (vm, screen) = open(metadata(media = listOf(voice(VOX1))))
        vm.onAction(EditAction.RemoveVoice(VOX1))
        runCurrent()
        assertEquals(listOf(EditVoiceNote(VOX1, 20_000)), screen.value.content.voiceNotes)
        assertEquals(emptyList<String>(), h.entries.removed)
    }
}
