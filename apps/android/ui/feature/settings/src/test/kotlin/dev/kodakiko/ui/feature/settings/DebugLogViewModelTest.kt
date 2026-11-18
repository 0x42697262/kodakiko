@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import dev.kodakiko.log.DebugLogInfo
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
class DebugLogViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()
    private val port get() = h.debugLog

    private class Opened(val vm: DebugLogViewModel, val events: MutableList<SettingsEvent>) {
        val state: DebugLogState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = DebugLogViewModel(h.advancedDeps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun seed(vararg lines: String) {
        port.linesFlow.value = lines.toList()
        port.infoFlow.value = DebugLogInfo(enabled = true, sizeBytes = lines.sumOf { it.length + 1 }.toLong(), entries = lines.size)
    }

    @Test
    fun theStateFollowsTheLogInfoAndLines() = runTest {
        val o = open()
        assertEquals(DebugLogState(), o.state)
        seed("a", "b")
        runCurrent()
        assertEquals(listOf("a", "b"), o.state.lines)
        assertEquals(DebugLogInfo(enabled = true, sizeBytes = 4, entries = 2), o.state.info)
    }

    @Test
    fun theSwitchTurnsLoggingOnAndOff() = runTest {
        val o = open()
        o.vm.onAction(DebugLogAction.SetEnabled(true))
        runCurrent()
        assertTrue(o.state.info.enabled)
        o.vm.onAction(DebugLogAction.SetEnabled(false))
        runCurrent()
        assertFalse(o.state.info.enabled)
    }

    @Test
    fun copyReportsHowManyLinesWereCopiedUntilTheMessageIsShown() = runTest {
        seed("a", "b", "c")
        val o = open()
        o.vm.onAction(DebugLogAction.Copy)
        runCurrent()
        assertEquals(1, port.copies)
        assertEquals(DebugLogMessage.Copied(3), o.state.message)
        o.vm.onAction(DebugLogAction.MessageShown)
        runCurrent()
        assertNull(o.state.message)
    }

    @Test
    fun shareHandsTheLogToThePort() = runTest {
        val o = open()
        o.vm.onAction(DebugLogAction.Share)
        runCurrent()
        assertEquals(1, port.shares)
        assertNull(o.state.message)
    }

    @Test
    fun clearAsksFirstAndDismissKeepsTheLog() = runTest {
        seed("a")
        val o = open()
        o.vm.onAction(DebugLogAction.AskClear)
        runCurrent()
        assertTrue(o.state.confirmingClear)
        o.vm.onAction(DebugLogAction.DismissClear)
        runCurrent()
        assertFalse(o.state.confirmingClear)
        assertEquals(listOf("a"), o.state.lines)
    }

    @Test
    fun confirmingClearEmptiesTheLogAndSaysSo() = runTest {
        seed("a", "b")
        val o = open()
        o.vm.onAction(DebugLogAction.AskClear)
        o.vm.onAction(DebugLogAction.ConfirmClear)
        runCurrent()
        assertFalse(o.state.confirmingClear)
        assertEquals(emptyList<String>(), o.state.lines)
        assertEquals(0, o.state.info.entries)
        assertEquals(DebugLogMessage.Cleared, o.state.message)
    }

    @Test
    fun aFailingPortShowsTheFailedMessage() = runTest {
        seed("a")
        port.failure = IllegalStateException("disk")
        val o = open()
        o.vm.onAction(DebugLogAction.Copy)
        runCurrent()
        assertEquals(DebugLogMessage.Failed, o.state.message)
        o.vm.onAction(DebugLogAction.MessageShown)
        o.vm.onAction(DebugLogAction.AskClear)
        o.vm.onAction(DebugLogAction.ConfirmClear)
        runCurrent()
        assertEquals(DebugLogMessage.Failed, o.state.message)
        assertEquals(listOf("a"), o.state.lines)
        o.vm.onAction(DebugLogAction.MessageShown)
        o.vm.onAction(DebugLogAction.Share)
        runCurrent()
        assertEquals(DebugLogMessage.Failed, o.state.message)
        assertEquals(0, port.shares)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(DebugLogAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}
