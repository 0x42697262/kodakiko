@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.TimeFormat
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.markdown.EditedText
import dev.kodakiko.markdown.MarkdownAction
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.markdown.applyMarkdownAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = EditHarness()

    private fun at(start: Int, end: Int = start) = TextSelection(start, end)

    private fun TestScope.open(note: String = "**swim** at the lake"): Pair<EditViewModel, Observed<EditState, EditEvent>> {
        h.store(metadata(note = note))
        val vm = EditViewModel(h.deps, ENTRY, false)
        clearAtEnd(vm)
        return vm to observe(vm.state, vm.events, h.log)
    }

    private fun EditViewModel.type(text: String, selection: TextSelection) = onAction(EditAction.NoteChanged(text, selection))

    private fun Observed<EditState, EditEvent>.edited(): EditedText =
        EditedText(requireNotNull(value.body).note, requireNotNull(value.noteEditor).selection)

    private fun TestScope.run(text: String, selection: TextSelection, action: MarkdownAction): EditedText {
        val (vm, screen) = open(text)
        vm.onAction(EditAction.EditNote)
        vm.type(text, selection)
        vm.onAction(EditAction.Toolbar(action))
        return screen.edited()
    }

    @Test
    fun thePencilOpensTheEditorWithTheCursorAtTheEnd() = runTest {
        val (vm, screen) = open()
        assertNull(screen.value.noteEditor)
        vm.onAction(EditAction.EditNote)
        assertEquals(NoteEditorUi(at(20)), screen.value.noteEditor)
        vm.onAction(EditAction.NoteDone)
        assertNull(screen.value.noteEditor)
        assertEquals("**swim** at the lake", screen.value.body?.note)
    }

    @Test
    fun aNewEntryStartsWithAnEmptyNote() = runTest {
        val (vm, screen) = open("")
        assertEquals("", screen.value.body?.note)
        vm.onAction(EditAction.EditNote)
        assertEquals(NoteEditorUi(at(0)), screen.value.noteEditor)
    }

    @Test
    fun typingMovesTheSelectionTheToolbarUses() = runTest {
        val (vm, screen) = open("")
        vm.onAction(EditAction.EditNote)
        vm.type("a big day", at(2, 5))
        assertEquals(NoteEditorUi(at(2, 5)), screen.value.noteEditor)
        assertEquals("a big day", screen.value.body?.note)
    }

    @Test
    fun wrapsASelectionInBoldItalicAndStrikethroughMarkers() = runTest {
        assertEquals(EditedText("a **big** day", at(4, 7)), run("a big day", at(2, 5), MarkdownAction.BOLD))
        assertEquals("a *big* day", run("a big day", at(2, 5), MarkdownAction.ITALIC).text)
        assertEquals("a ~~big~~ day", run("a big day", at(2, 5), MarkdownAction.STRIKE).text)
    }

    @Test
    fun insertsAnEmptyPairAndPutsTheCursorBetweenItWhenNothingIsSelected() = runTest {
        assertEquals(EditedText("a****b", at(3)), run("ab", at(1), MarkdownAction.BOLD))
    }

    @Test
    fun cyclesAHeadingAtTheStartOfTheCurrentLine() = runTest {
        val (vm, screen) = open("")
        vm.onAction(EditAction.EditNote)
        vm.type("one\ntwo", at(5))
        vm.onAction(EditAction.Toolbar(MarkdownAction.HEADING))
        assertEquals("one\n# two", screen.edited().text)
        vm.onAction(EditAction.Toolbar(MarkdownAction.HEADING))
        assertEquals("one\n## two", screen.edited().text)
        vm.onAction(EditAction.Toolbar(MarkdownAction.HEADING))
        assertEquals("one\ntwo", screen.edited().text)
    }

    @Test
    fun addsListChecklistAndQuotePrefixesToTheCurrentLine() = runTest {
        assertEquals("- milk", run("milk", at(2), MarkdownAction.BULLET).text)
        assertEquals("- [ ] milk", run("milk", at(2), MarkdownAction.CHECKLIST).text)
        assertEquals("> said", run("said", at(0), MarkdownAction.QUOTE).text)
    }

    @Test
    fun turnsASelectionIntoALinkWithTheCursorOnTheAddress() = runTest {
        assertEquals(EditedText("see [site](url)", at(11, 14)), run("see site", at(4, 8), MarkdownAction.LINK))
    }

    @Test
    fun insertsTheCurrentTimeFromTheClockAtTheCursor() = runTest {
        assertEquals(EditedText("at 13:00", at(8)), run("at ", at(3), MarkdownAction.TIME))
    }

    @Test
    fun theInsertedTimeFollowsTheDeviceOffsetAndThe12HourSetting() = runTest {
        h.clock.offset = -300
        assertEquals("at 07:00", run("at ", at(3), MarkdownAction.TIME).text)
        h.clock.offset = 60
        h.clock.nowMs = NOW_MS + 9 * 60_000
        h.settings.value = DeviceSettingsDefaults.ANDROID.copy(timeFormat = TimeFormat.HOURS_12)
        assertEquals(EditedText("at 1:09 PM", at(10)), run("at ", at(3), MarkdownAction.TIME))
    }

    @Test
    fun everyToolbarActionMatchesTheSharedMarkdownToolbar() = runTest {
        val text = "first line\nsecond words here"
        val selection = at(18, 23)
        for (action in MarkdownAction.entries) {
            assertEquals(action.wire, applyMarkdownAction(action, text, selection, "13:00"), run(text, selection, action))
        }
    }

    @Test
    fun toolbarEditsAreSavedLikeTyping() = runTest {
        val (vm, _) = open("")
        vm.onAction(EditAction.EditNote)
        vm.type("Lake day ", at(9))
        vm.onAction(EditAction.Toolbar(MarkdownAction.BOLD))
        runCurrent()
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf(EntryEdit(note = "Lake day ****")), h.entries.edits)
    }

    @Test
    fun theU34SequenceEndsWithTheMarkdownStored() = runTest {
        val (vm, screen) = open("")
        vm.onAction(EditAction.EditNote)
        vm.type("Lake day ", at(9))
        vm.onAction(EditAction.Toolbar(MarkdownAction.BOLD))
        assertEquals(EditedText("Lake day ****", at(11)), screen.edited())
        vm.type("Lake day **swim**", at(15))
        vm.onAction(EditAction.Done)
        runCurrent()
        assertEquals("Lake day **swim**", h.entries.meta().note)
        assertEquals(listOf<EditEvent>(EditEvent.Back), screen.events)
    }
}
