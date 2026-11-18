@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.markdown.TextSelection
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = EditHarness()

    private val placed = metadata(tags = listOf("beach", "family"), location = PARIS)

    private fun TestScope.open(meta: EntryMetadataV1 = placed): Pair<EditViewModel, Observed<EditState, EditEvent>> {
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
    fun thePencilOpensTheTimeEditorWithTheLocalDateAndTime() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleTimeEditor)
        assertEquals(TimeEditorUi("2026-11-21", "09:00", invalid = false), screen.value.timeEditor)
        vm.onAction(EditAction.ToggleTimeEditor)
        assertNull(screen.value.timeEditor)
    }

    @Test
    fun doneSetsTheNewTimeKeepsTheOffsetAndItIsWrittenOnSave() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleTimeEditor)
        vm.onAction(EditAction.ApplyTime("2026-11-20", "18:30"))
        runCurrent()
        assertNull(screen.value.timeEditor)
        assertEquals(EditTime("2026-11-20T17:30:00.000Z", 60, NOW_UTC, DEVICE_OFFSET), screen.value.content.time)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        save(vm)
        assertEquals(listOf(EntryEdit(time = EntryTimeEdit("2026-11-20T17:30:00.000Z", 60))), h.entries.edits)
    }

    @Test
    fun theEditorUsesTheEntryOffsetNotTheDeviceOffset() = runTest {
        val (vm, screen) = open(metadata(takenAtUtc = "2026-11-21T15:00:00.000Z", utcOffsetMinutes = -300))
        vm.onAction(EditAction.ToggleTimeEditor)
        assertEquals(TimeEditorUi("2026-11-21", "10:00", invalid = false), screen.value.timeEditor)
        vm.onAction(EditAction.ApplyTime("2026-11-21", "11:15"))
        save(vm)
        assertEquals(EntryTimeEdit("2026-11-21T16:15:00.000Z", -300), h.entries.edits.single().time)
    }

    @Test
    fun invalidInputShowsTheFormatHintAndChangesNothing() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleTimeEditor)
        vm.onAction(EditAction.ApplyTime("2026-02-30", "10:00"))
        assertEquals(TimeEditorUi("2026-02-30", "10:00", invalid = true), screen.value.timeEditor)
        vm.onAction(EditAction.ApplyTime("2026-11-11", "24:00"))
        assertEquals(TimeEditorUi("2026-11-11", "24:00", invalid = true), screen.value.timeEditor)
        vm.onAction(EditAction.ApplyTime("11/11/2026", "2pm"))
        assertEquals(TimeEditorUi("11/11/2026", "2pm", invalid = true), screen.value.timeEditor)
        runCurrent()
        assertEquals("2026-11-21T08:00:00.000Z", screen.value.content.time.takenAtUtc)
        vm.onAction(EditAction.ApplyTime("2026-11-11", "14:05"))
        runCurrent()
        assertNull(screen.value.timeEditor)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        save(vm)
        assertEquals(EntryTimeEdit("2026-11-11T13:05:00.000Z", 60), h.entries.edits.single().time)
    }

    @Test
    fun useOriginalTimeGoesBackToTheTimeThePageOpenedWith() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ToggleTimeEditor)
        vm.onAction(EditAction.ApplyTime("2026-11-20", "18:30"))
        runCurrent()
        vm.onAction(EditAction.ToggleTimeEditor)
        vm.onAction(EditAction.ResetTime)
        runCurrent()
        assertNull(screen.value.timeEditor)
        assertEquals("2026-11-21T08:00:00.000Z", screen.value.content.time.takenAtUtc)
        save(vm)
        assertEquals("2026-11-21T08:00:00.000Z", h.entries.meta().takenAtUtc)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
    }

    @Test
    fun theMapIconOpensTheMapsAppAtThePlace() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.OpenMap)
        runCurrent()
        assertEquals(listOf<EditEvent>(EditEvent.OpenUri("geo:48.8584,2.2945?q=48.8584,2.2945")), screen.events)
    }

    @Test
    fun removingThePlaceHidesTheRowAndIsWrittenOnSave() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.RemoveLocation)
        runCurrent()
        assertNull(screen.value.content.location)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        assertEquals(PARIS, h.entries.meta().location)
        vm.onAction(EditAction.OpenMap)
        runCurrent()
        assertEquals(emptyList<EditEvent>(), screen.events)
        save(vm)
        assertEquals(listOf(EntryEdit(location = OptionalValue.Present(null))), h.entries.edits)
        assertNull(h.entries.meta().location)
    }

    @Test
    fun aDevicePlaceKeepsItsSource() = runTest {
        val (_, screen) = open(metadata(location = NEAR_HOME))
        assertEquals(NEAR_HOME, screen.value.content.location)
    }

    @Test
    fun chooseTagsListsEveryTagInTheTagOrder() = runTest {
        h.tagged("city", "zoo")
        val (vm, screen) = open()
        vm.onAction(EditAction.ChooseTags)
        assertEquals(EditSheet.ChooseTags(listOf("beach", "city", "family", "zoo")), screen.value.sheet)
        vm.onAction(EditAction.DismissSheet)
        h.settings.value = DeviceSettingsDefaults.ANDROID.copy(tagOrder = listOf("zoo", "city"))
        vm.onAction(EditAction.ChooseTags)
        assertEquals(EditSheet.ChooseTags(listOf("zoo", "city", "beach", "family")), screen.value.sheet)
    }

    @Test
    fun checkingATagAddsItAtOnceAndTheDialogStaysOpen() = runTest {
        h.tagged("city")
        val (vm, screen) = open()
        vm.onAction(EditAction.ChooseTags)
        vm.onAction(EditAction.ToggleTag("city"))
        runCurrent()
        assertEquals(listOf("beach", "family", "city"), screen.value.content.tags)
        assertEquals(EditSheet.ChooseTags(listOf("beach", "city", "family")), screen.value.sheet)
        vm.onAction(EditAction.ToggleTag("beach"))
        runCurrent()
        assertEquals(listOf("family", "city"), screen.value.content.tags)
        assertEquals(emptyList<EntryEdit>(), h.entries.edits)
        vm.onAction(EditAction.DismissSheet)
        save(vm)
        assertEquals(listOf(EntryEdit(tags = listOf("family", "city"))), h.entries.edits)
        assertEquals(listOf("city", "family"), h.entries.meta().tags)
    }

    @Test
    fun anEntryHoldsAtMost50Tags() = runTest {
        val fifty = (0 until 50).map { "t$it" }
        h.tagged("extra")
        val (vm, screen) = open(metadata(tags = fifty))
        vm.onAction(EditAction.ChooseTags)
        vm.onAction(EditAction.ToggleTag("extra"))
        runCurrent()
        assertEquals(fifty, screen.value.content.tags)
        vm.onAction(EditAction.ToggleTag("t0"))
        runCurrent()
        assertEquals(49, screen.value.content.tags.size)
        vm.onAction(EditAction.ToggleTag("extra"))
        assertEquals(50, screen.value.content.tags.size)
        vm.onAction(EditAction.ToggleTag("t1"))
        assertEquals(49, screen.value.content.tags.size)
        save(vm)
        assertEquals(1, h.entries.edits.size)
        assertEquals(fifty.drop(2) + "extra", h.entries.edits.single().tags)
    }

    @Test
    fun aChipsRemoveButtonDropsThatTag() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.RemoveTag("beach"))
        runCurrent()
        assertEquals(listOf("family"), screen.value.content.tags)
        save(vm)
        assertEquals(listOf(EntryEdit(tags = listOf("family"))), h.entries.edits)
    }

    @Test
    fun editTagsClosesTheDialogAndOpensTheTagEditorKeepingTheDraft() = runTest {
        val (vm, screen) = open()
        vm.onAction(EditAction.ChooseTags)
        vm.onAction(EditAction.NoteChanged("tagged", TextSelection(6, 6)))
        vm.onAction(EditAction.EditTags)
        runCurrent()
        assertNull(screen.value.sheet)
        assertEquals("", h.entries.meta().note)
        assertEquals(listOf<EditEvent>(EditEvent.Open(Route.Tags)), screen.events)
        vm.onAction(EditAction.ScreenVisible(true))
        runCurrent()
        assertEquals("tagged", screen.value.content.note)
        save(vm)
        assertEquals("tagged", h.entries.meta().note)
    }
}
