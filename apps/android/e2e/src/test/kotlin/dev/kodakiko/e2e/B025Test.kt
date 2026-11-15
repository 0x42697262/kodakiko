package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.service.entries.BulkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B025Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    private fun smallEntry() = EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "to trash", emptyList())

    @Test
    fun whenTheUserMovesItToTheTrashAndThenRestoresItThenItLeavesAndReturnsOnEveryDevice() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        b.syncUntilIdle()
        assertNull(b.readEntry(entry.entryId)?.trashedAt)

        a.entries.trash(listOf(entry.entryId))
        a.syncUntilIdle()
        b.syncUntilIdle()

        assertNotNull(a.readEntry(entry.entryId)?.trashedAt)
        assertNotNull(b.readEntry(entry.entryId)?.trashedAt)
        assertNotNull(server.findServerEntry(entry.entryId)?.trashedAt)

        a.entries.restore(listOf(entry.entryId))
        a.syncUntilIdle()
        b.syncUntilIdle()

        assertNull(a.readEntry(entry.entryId)?.trashedAt)
        assertNull(b.readEntry(entry.entryId)?.trashedAt)
        assertNull(server.findServerEntry(entry.entryId)?.trashedAt)
    }

    @Test
    fun whenTheUserTrashesAndRestoresItOfflineThenNothingIsSentAndTheOtherDeviceNeverSeesAChange() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        b.syncUntilIdle()
        a.fetcher.setOffline(true)

        assertEquals(BulkResult(changed = 1, skipped = 0), a.entries.trash(listOf(entry.entryId)))
        assertEquals(BulkResult(changed = 1, skipped = 0), a.entries.restore(listOf(entry.entryId)))

        assertEquals(0, a.outboxRows().size)
        a.fetcher.setOffline(false)
        a.fetcher.clearRequests()
        a.syncUntilIdle()
        assertEquals(emptyList<String>(), a.fetcher.requests().filter { it.startsWith(E2eConstants.POST_METHOD) })
        assertNull(server.findServerEntry(entry.entryId)?.trashedAt)
        b.syncUntilIdle()
        assertNull(b.readEntry(entry.entryId)?.trashedAt)
    }

    @Test
    fun whenTheUserDeletesATrashedEntryPermanentlyThenItDisappearsOnEveryDevice() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        b.syncUntilIdle()
        a.entries.trash(listOf(entry.entryId))
        a.syncUntilIdle()

        a.entries.deletePermanently(listOf(entry.entryId))
        a.syncUntilIdle()
        b.syncUntilIdle()

        assertNull(a.readEntry(entry.entryId))
        assertNull(b.readEntry(entry.entryId))
        assertNull(server.findServerEntry(entry.entryId))
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
