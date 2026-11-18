package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.service.entries.BulkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagsTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun scenario10DeviceATags3EntriesOfflineAndAfterSyncDeviceBSeesTheTagOnAll3() = e2e {
        val (a, b) = startPair()
        val entries = mutableListOf<CreatedEntry>()
        for (tags in listOf(listOf("a"), listOf("b"), emptyList())) {
            entries += a.createSyncedEntry(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "entry", tags))
        }
        b.syncUntilIdle()
        val ids = entries.map { it.entryId }
        a.fetcher.setOffline(true)

        val result = a.entries.addTagToMany(ids, ADDED_TAG)

        assertEquals(BulkResult(changed = 3, skipped = 0), result)
        assertEquals(3, a.outboxRows().size)
        a.fetcher.setOffline(false)
        a.syncUntilIdle()
        b.syncUntilIdle()

        for (id in ids) assertTrue(ADDED_TAG in b.readMetadata(id).tags)
        assertEquals(listOf("a", ADDED_TAG), b.readMetadata(ids[0]).tags)
    }

    companion object {
        private const val ADDED_TAG = "summer"

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
