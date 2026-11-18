@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntryNewViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val log = mutableListOf<String>()
    private val entries = FakeEntries(log)

    @Test
    fun createsTheEntryFromTheTrayAndOpensItsEditPageAsNew() = runTest {
        entries.createResult = { "fresh-entry" }
        val vm = EntryNewViewModel(EntryNewDeps(entries = entries, trayIsEmpty = { false }))
        val screen = observe(vm.state, vm.events)
        runCurrent()
        assertEquals(listOf(CreateEntryInput(note = "", tags = emptyList(), time = null)), entries.created)
        assertEquals(listOf<EntryNewEvent>(EntryNewEvent.Replace(Route.EntryEdit("fresh-entry", isNew = true))), screen.events)
        assertFalse(screen.value.failed)
    }

    @Test
    fun anEmptyTrayGoesBackToTheCamera() = runTest {
        val vm = EntryNewViewModel(EntryNewDeps(entries = entries, trayIsEmpty = { true }))
        val screen = observe(vm.state, vm.events)
        runCurrent()
        assertEquals(emptyList<CreateEntryInput>(), entries.created)
        assertEquals(listOf<EntryNewEvent>(EntryNewEvent.Replace(Route.Capture())), screen.events)
    }

    @Test
    fun aFailedCreateShowsTheErrorAndStays() = runTest {
        entries.createResult = { throw IllegalStateException("tray changed") }
        val vm = EntryNewViewModel(EntryNewDeps(entries = entries, trayIsEmpty = { false }))
        val screen = observe(vm.state, vm.events)
        runCurrent()
        assertTrue(screen.value.failed)
        assertEquals(emptyList<EntryNewEvent>(), screen.events)
    }

    @Test
    fun theEntryIsCreatedOnlyOnce() = runTest {
        val vm = EntryNewViewModel(EntryNewDeps(entries = entries, trayIsEmpty = { false }))
        val first = observe(vm.state, vm.events)
        runCurrent()
        observe(vm.state, vm.events)
        runCurrent()
        assertEquals(1, entries.created.size)
        assertEquals(1, first.events.size)
    }
}
