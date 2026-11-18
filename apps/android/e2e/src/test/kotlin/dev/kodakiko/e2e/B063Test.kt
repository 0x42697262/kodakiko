package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B063Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    private fun smallEntry() = EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "entry", emptyList())

    @Test
    fun scenario8WhenADeviceWithAnOldCursorSyncsThenItResyncsAndEndsIdenticalToTheOtherDevice() = e2e {
        val (a, b) = startPair()
        val kept = a.createSyncedEntry(smallEntry())
        val doomed = a.createSyncedEntry(smallEntry())
        b.syncUntilIdle()
        assertEquals(2, b.entrySnapshots().size)

        a.deleteEntryLocally(doomed.entryId)
        a.syncUntilIdle()
        server.pruneTombstonesWithMovedClock()
        b.fetcher.clearRequests()
        b.syncUntilIdle()

        assertTrue(b.fetcher.requests().any { it.contains(E2eConstants.DIFF_PATH_FRAGMENT + E2eConstants.FROM_ZERO_SUFFIX) })
        val onA = a.entrySnapshots()
        assertEquals(listOf(kept.entryId), onA.keys.toList())
        assertEquals(onA, b.entrySnapshots())
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
