package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.contract.Routes
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.OutboxState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    private fun smallEntry() = EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), BASE_NOTE, listOf(BASE_TAG))

    @Test
    fun scenario1AnEntryWithTwoMediaOneMultipartReachesDeviceBAndDecryptsThere() = e2e {
        val (a, b) = startPair()
        val big = randomMedia(E2eConstants.MULTIPART_MEDIA_BYTES)
        val small = randomMedia(E2eConstants.SMALL_MEDIA_BYTES)
        val entry = a.createEntryLocally(EntryInput(listOf(big, small), "two photos", listOf(BASE_TAG)))

        a.syncUntilIdle()

        val onA = a.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.SYNCED, onA.localState)
        assertEquals(E2eConstants.FIRST_REVISION, onA.revision)
        val multipartParts = a.fetcher.requests()
            .filter { it.startsWith(E2eConstants.PUT_METHOD) && it.contains(E2eConstants.PART_NUMBER_QUERY) }
        assertTrue(multipartParts.size >= 2)

        b.syncUntilIdle()

        val onB = b.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.SYNCED, onB.localState)
        assertEquals(E2eConstants.FIRST_REVISION, onB.revision)
        val (media, objects) = b.transaction { tx ->
            val mediaRows = tx.media.byEntry(entry.entryId)
            mediaRows to mediaRows.flatMap { tx.objects.byMedia(it.id) }
        }
        assertEquals(entry.mediaIds.sorted(), media.map { it.id }.sorted())
        assertEquals(entry.mediaIds.size * 2, objects.size)
        assertTrue(objects.all { it.uploadState == ObjectUploadState.UPLOADED })
        assertTrue(objects.all { it.localState == ObjectLocalState.MISSING })

        val bigPlaintext = b.downloadAndDecrypt(entry.entryId, entry.mediaIdAt(0), ObjectVariant.ORIGINAL)
        val smallPlaintext = b.downloadAndDecrypt(entry.entryId, entry.mediaIdAt(1), ObjectVariant.ORIGINAL)
        assertArrayEquals(big, bigPlaintext)
        assertArrayEquals(small, smallPlaintext)
    }

    @Test
    fun scenario3ADroppedPatchResponseCompletesThroughTheEquivalentMergePathWithoutASecondRevisionBump() = e2e {
        val (a, _) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        a.editEntryLocally(entry.entryId, { it.copy(note = "edited once") }, E2eConstants.LATER_EDIT_UTC)
        a.fetcher.dropNextResponse(E2eConstants.PATCH_METHOD)

        a.syncUntilIdle()

        assertEquals(1, a.fetcher.droppedCount())
        val row = a.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.SYNCED, row.localState)
        assertEquals(E2eConstants.SECOND_REVISION, row.revision)
        assertEquals(E2eConstants.SECOND_REVISION, server.serverEntry(entry.entryId).revision)
        assertEquals(0, a.outboxRows().size)
    }

    @Test
    fun scenario4ATrashOnAAndAnEditOnBEndTrashedOnBothWithTheEditApplied() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        b.syncUntilIdle()

        a.trashEntryLocally(entry.entryId)
        a.syncUntilIdle()
        b.editEntryLocally(entry.entryId, { it.copy(note = "edited on B") }, E2eConstants.LATER_EDIT_UTC)
        b.syncUntilIdle()
        a.syncUntilIdle()

        for (device in listOf(a, b)) {
            assertNotNull(device.readEntry(entry.entryId)?.trashedAt)
            assertEquals("edited on B", device.readMetadata(entry.entryId).note)
        }
    }

    @Test
    fun scenario5ADeleteOnAAndAnEditOnBDropTheUpdateItemAndRemoveTheEntryOnB() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        b.syncUntilIdle()

        a.deleteEntryLocally(entry.entryId)
        a.syncUntilIdle()
        b.editEntryLocally(entry.entryId, { it.copy(note = "edited after delete") }, E2eConstants.LATER_EDIT_UTC)
        b.syncUntilIdle()

        assertNull(a.readEntry(entry.entryId))
        assertNull(b.readEntry(entry.entryId))
        assertEquals(0, b.outboxRows().size)
        assertEquals(0, b.sync.status().dead)
    }

    @Test
    fun scenario6AFullQuotaEndsTheCreateItemDeadWithQuotaExceededAndRetryFinishesItAfterTheQuotaIsRaised() = e2e {
        val (a, b) = startPair()
        server.setQuota(a.userId, E2eConstants.TINY_QUOTA_BYTES)
        val entry = a.createEntryLocally(smallEntry())

        a.syncUntilIdle()

        val dead = a.outboxRows()
        assertEquals(1, dead.size)
        assertEquals(E2eConstants.CREATE_ENTRY_KIND, dead[0].kind)
        assertEquals(OutboxState.DEAD, dead[0].state)
        assertEquals(E2eConstants.QUOTA_EXCEEDED_CODE, dead[0].lastErrorCode)
        assertEquals(1, a.sync.status().dead)

        server.setQuota(a.userId, null)
        a.sync.retryDead()
        a.syncUntilIdle()

        assertEquals(0, a.outboxRows().size)
        assertEquals(EntryLocalState.SYNCED, a.readEntry(entry.entryId)?.localState)
        b.syncUntilIdle()
        assertNotNull(b.readEntry(entry.entryId))
    }

    @Test
    fun scenario7APulledRevisionOlderThanTheLocalOneIsRejectedAndLogged() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry())
        val older = server.serverEntry(entry.entryId)
        a.editEntryLocally(entry.entryId, { it.copy(note = "newer note") }, E2eConstants.LATER_EDIT_UTC)
        a.syncUntilIdle()
        b.syncUntilIdle()
        val before = b.entrySnapshots()
        assertEquals(E2eConstants.SECOND_REVISION, before[entry.entryId]?.revision)

        server.rewindEntry(entry.entryId, a.homeCollectionId(), older)
        b.syncUntilIdle()

        assertTrue(b.logs.any { it.event == ROLLBACK_REJECTED_EVENT })
        assertEquals(before, b.entrySnapshots())
        assertEquals("newer note", b.readMetadata(entry.entryId).note)
    }

    @Test
    fun scenario9ADroppedCreateEntryResponseFollowedByAnEditStillEndsSyncedPulledServerCopy() = e2e {
        val (a, _) = startPair()
        val entry = a.createEntryLocally(smallEntry())
        a.fetcher.dropNextResponse(E2eConstants.POST_METHOD, E2eConstants.LOST_RESPONSE_COUNT)

        a.sync.syncNow()
        assertEquals(E2eConstants.LOST_RESPONSE_COUNT, a.fetcher.droppedCount())
        a.editEntryLocally(entry.entryId, { it.copy(note = EDITED_AFTER_LOSS) }, E2eConstants.LATER_EDIT_UTC)
        a.syncUntilIdle()

        val row = a.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.SYNCED, row.localState)
        assertEquals(E2eConstants.SECOND_REVISION, row.revision)
        assertEquals(E2eConstants.SECOND_REVISION, server.serverEntry(entry.entryId).revision)
        assertEquals(EDITED_AFTER_LOSS, a.readMetadata(entry.entryId).note)
        assertEquals(0, a.outboxRows().size)
    }

    @Test
    fun scenario9bTheSameLostResponseResolvesThrough409IdConflictWhenNoServerCopyWasPulled() = e2e {
        val (a, _) = startPair()
        val entry = a.createEntryLocally(smallEntry())
        a.fetcher.dropNextResponse(E2eConstants.POST_METHOD, E2eConstants.LOST_RESPONSE_COUNT)

        a.sync.syncNow()
        a.transaction { tx ->
            val row = tx.entries.get(entry.entryId)
            if (row != null) tx.entries.put(row.copy(serverPendingBlob = null, serverPendingRevision = null))
        }
        a.editEntryLocally(entry.entryId, { it.copy(note = EDITED_AFTER_LOSS) }, E2eConstants.LATER_EDIT_UTC)
        a.syncUntilIdle()

        val entryGets = a.fetcher.requests()
            .filter { it.startsWith(E2eConstants.GET_METHOD + E2eConstants.REQUEST_SEPARATOR + server.baseUrl + Routes.entries() + "/") }
        assertNotEquals(0, entryGets.size)
        val row = a.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.SYNCED, row.localState)
        assertEquals(E2eConstants.SECOND_REVISION, row.revision)
        assertEquals(E2eConstants.SECOND_REVISION, server.serverEntry(entry.entryId).revision)
        assertEquals(0, a.outboxRows().size)
    }

    companion object {
        private const val BASE_NOTE = "base note"
        private const val BASE_TAG = "family"
        private const val EDITED_AFTER_LOSS = "edited after the lost response"
        private const val ROLLBACK_REJECTED_EVENT = "sync.rollback_rejected"

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
