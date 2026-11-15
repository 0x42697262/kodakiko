package dev.kodakiko.data.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeoutHandlerTest {
    @Test
    fun onTimeoutStopsWorkersThenFailsPendingThenEmitsStoppedTimeoutThenStopsTheService() {
        val calls = mutableListOf<String>()
        val handler = TimeoutHandler(
            stopWorkers = { calls.add("stopWorkers") },
            failPending = { calls.add("failPending") },
            emitStopped = { reason -> calls.add("emit:${reason.wire}") },
            stopService = { calls.add("stopSelf") },
        )
        handler.onTimeout()
        assertEquals(listOf("stopWorkers", "failPending", "emit:timeout", "stopSelf"), calls)
    }

    @Test
    fun onTimeoutCallsStopSelfEvenWhenNothingWasRunning() {
        var stopped = 0
        val handler = TimeoutHandler(stopWorkers = {}, failPending = {}, emitStopped = {}, stopService = { stopped += 1 })
        handler.onTimeout()
        handler.onTimeout()
        assertEquals(2, stopped)
    }
}
