@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.tags

import dev.kodakiko.index.TagCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = TagsHarness()

    private fun TestScope.open(): Pair<TagsViewModel, StateFlow<TagsState>> {
        val vm = TagsViewModel(h.deps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    private fun TagsViewModel.type(name: String) = onAction(TagsAction.NameChanged(name))

    private fun rows(state: StateFlow<TagsState>): List<TagCount> = state.value.tags

    private fun names(state: StateFlow<TagsState>): List<String> = state.value.tags.map { it.tag }

    @Test
    fun rowsAreTheOwnTagsWithCountsInTheEditorsOrderThenTheRestAlphabetically() = runTest {
        h.index.put("e1", listOf("beach", "zoo"))
        h.index.put("e2", listOf("beach", "city"))
        h.index.put("e3", listOf("beach", "zoo"))
        h.settings.order(listOf("zoo", "later", "beach"))
        val (_, state) = open()
        assertEquals(listOf(TagCount("zoo", 2), TagCount("later", 0), TagCount("beach", 3), TagCount("city", 1)), rows(state))
        assertNull(state.value.dialog)
    }

    @Test
    fun tagsOnSharedOrTrashedEntriesAreNotCounted() = runTest {
        h.index.put("own", listOf("beach"))
        h.index.put("theirs", listOf("beach", "party"), isOwn = false, collectionIds = listOf(OTHER_COLLECTION))
        h.index.put("binned", listOf("beach", "old"), trashed = true)
        val (_, state) = open()
        assertEquals(listOf(TagCount("beach", 1)), rows(state))
    }

    @Test
    fun theListFollowsTheIndexAndTheSettings() = runTest {
        val (_, state) = open()
        assertEquals(emptyList<TagCount>(), rows(state))
        h.beachAndCity()
        runCurrent()
        assertEquals(listOf(TagCount("beach", 2), TagCount("city", 1)), rows(state))
        h.settings.order(listOf("city"))
        runCurrent()
        assertEquals(listOf("city", "beach"), names(state))
    }

    @Test
    fun withoutAnIndexOnlyTheEditorsTagsAreListed() = runTest {
        h.index.available = false
        h.settings.order(listOf("later"))
        val (_, state) = open()
        assertEquals(listOf(TagCount("later", 0)), rows(state))
    }

    @Test
    fun addCreatesAnEmptyTagAtTheEndOfTheFullOrder() = runTest {
        h.beachAndCity()
        val (vm, state) = open()
        vm.onAction(TagsAction.Add)
        assertEquals(TagsDialog.Add(name = "", invalid = false), state.value.dialog)
        vm.type("  Family   Trip ")
        assertEquals(TagsDialog.Add(name = "  Family   Trip ", invalid = false), state.value.dialog)
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(listOf("beach", "city", "family trip"), h.settings.tagOrder)
        assertEquals(listOf(TagCount("beach", 2), TagCount("city", 1), TagCount("family trip", 0)), rows(state))
        assertEquals(emptyList<TagCall>(), h.entries.calls)
    }

    @Test
    fun addingATagThatIsAlreadyListedChangesNothing() = runTest {
        h.beachAndCity()
        val (vm, state) = open()
        vm.onAction(TagsAction.Add)
        vm.type("BEACH")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(emptyList<List<String>>(), h.settings.writes)
        assertEquals(emptyList<String>(), h.settings.tagOrder)
    }

    @Test
    fun anEmptyOrTooLongNameIsRefusedAndTheDialogStays() = runTest {
        val (vm, state) = open()
        vm.onAction(TagsAction.Add)
        vm.type("   ")
        vm.onAction(TagsAction.Save)
        assertEquals(TagsDialog.Add(name = "   ", invalid = true), state.value.dialog)
        val long = "a".repeat(65)
        vm.type(long)
        vm.onAction(TagsAction.Save)
        assertEquals(long, (state.value.dialog as TagsDialog.Add).name)
        assertEquals(true, (state.value.dialog as TagsDialog.Add).invalid)
        vm.type("a".repeat(64))
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(listOf("a".repeat(64)), h.settings.tagOrder)
    }

    @Test
    fun cancelClosesTheDialogAndForgetsTheName() = runTest {
        val (vm, state) = open()
        vm.onAction(TagsAction.Add)
        vm.type(" ")
        vm.onAction(TagsAction.Save)
        vm.onAction(TagsAction.Dismiss)
        assertNull(state.value.dialog)
        vm.onAction(TagsAction.Add)
        assertEquals(TagsDialog.Add(name = "", invalid = false), state.value.dialog)
        assertEquals(emptyList<List<String>>(), h.settings.writes)
    }

    @Test
    fun u28RenameAppliesToEveryEntryWithTheTagAndKeepsItsPlace() = runTest {
        h.beachAndCity()
        val (vm, state) = open()
        assertEquals(TagCount("beach", 2), rows(state)[0])
        vm.onAction(TagsAction.Rename("beach"))
        assertEquals(TagsDialog.Rename(tag = "beach", name = "beach", invalid = false), state.value.dialog)
        vm.type("Seaside")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(listOf(TagCall(FakeTagEntries.RENAME, listOf("e2", "e1"), "beach", "seaside")), h.entries.calls)
        assertEquals(listOf("seaside", "city"), h.settings.tagOrder)
        assertEquals(listOf(TagCount("seaside", 2), TagCount("city", 1)), rows(state))
    }

    @Test
    fun aRenamedTagKeepsItsPlaceInTheMiddleOfTheOrder() = runTest {
        h.beachAndCity()
        h.index.put("e3", listOf("zoo"))
        h.settings.order(listOf("zoo", "beach", "later", "city"))
        val (vm, _) = open()
        vm.onAction(TagsAction.Rename("beach"))
        vm.type("sand")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertEquals(listOf("zoo", "sand", "later", "city"), h.settings.tagOrder)
    }

    @Test
    fun renameSkipsTrashedAndSharedEntries() = runTest {
        h.beachAndCity()
        h.index.put("binned", listOf("beach"), trashed = true)
        h.index.put("theirs", listOf("beach"), isOwn = false, collectionIds = listOf(OTHER_COLLECTION))
        val (vm, _) = open()
        vm.onAction(TagsAction.Rename("beach"))
        vm.type("seaside")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertEquals(listOf("e2", "e1"), h.entries.calls.single().entryIds)
    }

    @Test
    fun theDialogClosesAtOnceAndTheOrderChangesOnlyAfterTheEntriesWereRenamed() = runTest {
        h.beachAndCity()
        val gate = CompletableDeferred<Unit>()
        h.entries.gate = gate
        val (vm, state) = open()
        vm.onAction(TagsAction.Rename("beach"))
        vm.type("seaside")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(emptyList<List<String>>(), h.settings.writes)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(listOf("seaside", "city")), h.settings.writes)
    }

    @Test
    fun aFailedRenameLeavesTheOrderAlone() = runTest {
        h.beachAndCity()
        h.settings.order(listOf("city", "beach"))
        h.entries.failure = IllegalStateException("locked")
        val (vm, state) = open()
        vm.onAction(TagsAction.Rename("beach"))
        vm.type("seaside")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(emptyList<List<String>>(), h.settings.writes)
        assertEquals(listOf("city", "beach"), names(state))
    }

    @Test
    fun anInvalidNewNameIsRefusedWithoutTouchingTheEntries() = runTest {
        h.beachAndCity()
        val (vm, state) = open()
        vm.onAction(TagsAction.Rename("city"))
        vm.type("  ")
        vm.onAction(TagsAction.Save)
        runCurrent()
        assertEquals(TagsDialog.Rename(tag = "city", name = "  ", invalid = true), state.value.dialog)
        assertEquals(emptyList<TagCall>(), h.entries.calls)
    }

    @Test
    fun deleteAsksWithTheCountThenRemovesTheTagFromItsEntriesAndTheOrder() = runTest {
        h.beachAndCity()
        h.settings.order(listOf("city"))
        val (vm, state) = open()
        vm.onAction(TagsAction.Delete("beach"))
        assertEquals(TagsDialog.Delete(tag = "beach", count = 2), state.value.dialog)
        vm.onAction(TagsAction.ConfirmDelete)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(listOf(TagCall(FakeTagEntries.DELETE, listOf("e2", "e1"), "beach")), h.entries.calls)
        assertEquals(listOf("city"), h.settings.tagOrder)
        assertEquals(listOf(TagCount("city", 1)), rows(state))
    }

    @Test
    fun deletingAnEmptyTagJustDropsItFromTheList() = runTest {
        h.beachAndCity()
        h.settings.order(listOf("later", "beach"))
        val (vm, state) = open()
        vm.onAction(TagsAction.Delete("later"))
        assertEquals(TagsDialog.Delete(tag = "later", count = 0), state.value.dialog)
        vm.onAction(TagsAction.ConfirmDelete)
        runCurrent()
        assertEquals(listOf(TagCall(FakeTagEntries.DELETE, emptyList(), "later")), h.entries.calls)
        assertEquals(listOf("beach", "city"), h.settings.tagOrder)
    }

    @Test
    fun aFailedDeleteKeepsTheTag() = runTest {
        h.beachAndCity()
        h.entries.failure = IllegalStateException("locked")
        val (vm, state) = open()
        vm.onAction(TagsAction.Delete("city"))
        vm.onAction(TagsAction.ConfirmDelete)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(emptyList<List<String>>(), h.settings.writes)
        assertEquals(listOf("beach", "city"), names(state))
    }

    @Test
    fun cancellingTheDeleteQuestionDeletesNothing() = runTest {
        h.beachAndCity()
        val (vm, state) = open()
        vm.onAction(TagsAction.Delete("city"))
        vm.onAction(TagsAction.Dismiss)
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(emptyList<TagCall>(), h.entries.calls)
    }

    @Test
    fun movingARowWritesTheWholeNewOrder() = runTest {
        h.beachAndCity()
        h.index.put("e3", listOf("zoo"))
        h.settings.order(listOf("zoo"))
        val (vm, state) = open()
        assertEquals(listOf("zoo", "beach", "city"), names(state))
        vm.onAction(TagsAction.Move(from = 2, to = 1))
        runCurrent()
        assertEquals(listOf("zoo", "city", "beach"), h.settings.tagOrder)
        assertEquals(listOf("zoo", "city", "beach"), names(state))
        vm.onAction(TagsAction.Move(from = 0, to = 2))
        runCurrent()
        assertEquals(listOf("city", "beach", "zoo"), h.settings.tagOrder)
    }

    @Test
    fun movesPastTheEndsChangeNothing() = runTest {
        h.beachAndCity()
        val (vm, _) = open()
        vm.onAction(TagsAction.Move(from = 0, to = -1))
        vm.onAction(TagsAction.Move(from = 1, to = 2))
        vm.onAction(TagsAction.Move(from = 5, to = 0))
        runCurrent()
        assertEquals(emptyList<List<String>>(), h.settings.writes)
    }

    @Test
    fun saveAndConfirmWithoutADialogDoNothing() = runTest {
        h.beachAndCity()
        val (vm, state) = open()
        vm.onAction(TagsAction.Save)
        vm.onAction(TagsAction.ConfirmDelete)
        vm.onAction(TagsAction.NameChanged("x"))
        runCurrent()
        assertNull(state.value.dialog)
        assertEquals(emptyList<TagCall>(), h.entries.calls)
        assertEquals(emptyList<List<String>>(), h.settings.writes)
    }
}
