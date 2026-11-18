@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.edit

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
import org.junit.Assert.assertTrue
import org.junit.Test

class AutosaverTest {
    private val writes = mutableListOf<String>()
    private val errors = mutableListOf<Throwable>()

    private fun TestScope.saver(write: suspend (String) -> Unit = { writes += it }): Autosaver<String> =
        Autosaver(backgroundScope, write) { errors += it }

    @Test
    fun theDebounceIs600Milliseconds() {
        assertEquals(600L, EditConstants.AUTOSAVE_DEBOUNCE_MS)
    }

    @Test
    fun writesAnImmediateChangeAtOnce() = runTest {
        val saver = saver()
        saver.change("a", false)
        runCurrent()
        assertEquals(listOf("a"), writes)
        assertEquals(0L, currentTime)
    }

    @Test
    fun debouncesTypingAndWritesOnlyTheLatestValue() = runTest {
        val saver = saver()
        saver.change("h", true)
        saver.change("he", true)
        saver.change("hello", true)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), writes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("hello"), writes)
    }

    @Test
    fun eachChangeRestartsTheWait() = runTest {
        val saver = saver()
        saver.change("a", true)
        advanceTimeBy(400)
        saver.change("ab", true)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(emptyList<String>(), writes)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("ab"), writes)
    }

    @Test
    fun anImmediateChangeReplacesAPendingDebouncedOneAndWritesOnce() = runTest {
        val saver = saver()
        saver.change("typed", true)
        saver.change("typed and tagged", false)
        runCurrent()
        assertEquals(listOf("typed and tagged"), writes)
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(listOf("typed and tagged"), writes)
    }

    @Test
    fun flushWritesAPendingDebouncedValueAndWaitsForWritesInFlight() = runTest {
        val release = CompletableDeferred<Unit>()
        val saver = saver { value ->
            writes += value
            release.await()
        }
        saver.change("draft", true)
        val flushed = async { saver.flush() }
        runCurrent()
        assertEquals(listOf("draft"), writes)
        assertFalse(flushed.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(flushed.isCompleted)
        assertEquals(0L, currentTime)
    }

    @Test
    fun flushWithNothingPendingWritesNothing() = runTest {
        val saver = saver()
        saver.flush()
        assertEquals(emptyList<String>(), writes)
        saver.change("once", false)
        saver.flush()
        saver.flush()
        assertEquals(listOf("once"), writes)
    }

    @Test
    fun aFlushedValueIsNotWrittenAgainWhenTheTimerWouldHaveFired() = runTest {
        val saver = saver()
        saver.change("note", true)
        saver.flush()
        advanceTimeBy(EditConstants.AUTOSAVE_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(listOf("note"), writes)
    }

    @Test
    fun runsWritesOneAfterAnotherAndReportsFailures() = runTest {
        val saver = saver { value ->
            writes += value
            if (value == "bad") throw IllegalStateException("x")
        }
        saver.change("bad", false)
        saver.change("good", false)
        saver.flush()
        assertEquals(listOf("bad", "good"), writes)
        assertEquals(1, errors.size)
        assertEquals("x", errors.single().message)
    }

    @Test
    fun writesNeverOverlap() = runTest {
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var running = 0
        var most = 0
        val saver = saver { value ->
            val gate = gates[writes.size]
            running += 1
            most = maxOf(most, running)
            writes += value
            gate.await()
            running -= 1
        }
        saver.change("first", false)
        runCurrent()
        saver.change("second", false)
        runCurrent()
        assertEquals(listOf("first"), writes)
        gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), writes)
        gates[1].complete(Unit)
        saver.flush()
        assertEquals(1, most)
    }
}
