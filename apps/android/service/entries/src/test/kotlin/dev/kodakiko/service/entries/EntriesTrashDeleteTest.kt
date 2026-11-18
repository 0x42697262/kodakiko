package dev.kodakiko.service.entries

import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.domain.EntryEdit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

const val MISSING_ENTRY_ID = "ZZZZZZZZZZZZZZZZZZZZZZ"

@RunWith(RobolectricTestRunner::class)
class EntriesTrashDeleteTest : EntriesTestBase() {
    @Test
    fun b025TrashThenRestoreOfflineLeavesNoOutboxItemsOfThePair() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)

        harness.service.trash(listOf(entryId))
        assertEquals(FIRST_NOW_ISO, harness.entryRow(entryId).trashedAt)
        assertEquals(listOf("trashEntry"), harness.outboxKinds())

        harness.service.restore(listOf(entryId))
        assertNull(harness.entryRow(entryId).trashedAt)
        assertEquals(0, harness.outbox().size)
    }

    @Test
    fun b025RestoreThenTrashOfflineAlsoCancels() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.service.trash(listOf(entryId))
        harness.markSynced(entryId)
        harness.service.restore(listOf(entryId))
        assertEquals(listOf("restoreEntry"), harness.outboxKinds())
        harness.service.trash(listOf(entryId))
        assertEquals(0, harness.outbox().size)
    }

    @Test
    fun queuesOneTrashEntryForAnEntryThatWasSynced() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.service.trash(listOf(entryId))
        assertEquals(listOf("trashEntry"), harness.outboxKinds())
        assertEquals(EntryLocalState.SYNCED, harness.entryRow(entryId).localState)
    }

    @Test
    fun trashesSeveralEntriesInOneCallAndNotifiesOnceForAllOfThem() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(first)
        harness.markSynced(second)
        harness.changed.clear()
        harness.service.trash(listOf(first, second))
        assertEquals(listOf("trashEntry", "trashEntry"), harness.outboxKinds())
        assertEquals(listOf(listOf(first, second)), harness.changed)
    }

    @Test
    fun doesNotQueueASecondTrashEntryForAnEntryThatIsAlreadyTrashed() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.service.trash(listOf(entryId))
        harness.service.trash(listOf(entryId))
        assertEquals(listOf("trashEntry"), harness.outboxKinds())
    }

    @Test
    fun enqueuesItsOwnKindWhenTheOppositeItemWasAlreadyAttempted() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.service.trash(listOf(entryId))
        harness.setAttempts("trashEntry", 1)
        harness.service.restore(listOf(entryId))
        assertEquals(listOf("trashEntry", "restoreEntry"), harness.outboxKinds())
    }

    @Test
    fun enqueuesItsOwnKindWhenTheOppositeItemIsDead() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.service.trash(listOf(entryId))
        harness.fixture.rowStore.transaction { tx -> tx.outbox.all().forEach { tx.outbox.update(it.copy(state = OutboxState.DEAD)) } }
        harness.service.restore(listOf(entryId))
        assertEquals(listOf("trashEntry", "restoreEntry"), harness.outboxKinds())
    }

    @Test
    fun enqueuesItsOwnKindWhenTheEntryIsInFlight() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.service.trash(listOf(entryId))
        harness.hooks.inFlight += entryId
        harness.service.restore(listOf(entryId))
        assertEquals(listOf("trashEntry", "restoreEntry"), harness.outboxKinds())
    }

    @Test
    fun returnsChangedAndSkippedCounts() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val foreign = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.setOwner(foreign, OTHER_USER)
        harness.service.trash(listOf(first))
        assertEquals(BulkResult(changed = 1, skipped = 3), harness.service.trash(listOf(first, second, foreign, MISSING_ENTRY_ID)))
        assertEquals(BulkResult(changed = 2, skipped = 0), harness.service.restore(listOf(first, second)))
        assertEquals(BulkResult(changed = 1, skipped = 1), harness.service.deletePermanently(listOf(first, foreign)))
    }

    @Test
    fun trashSkipsEntriesThatAreNotOwned() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.markSynced(entryId)
        harness.setOwner(entryId, OTHER_USER)
        harness.service.trash(listOf(entryId))
        assertNull(harness.entryRow(entryId).trashedAt)
        assertEquals(0, harness.outbox().size)
    }

    @Test
    fun queuesNothingForAnEntryTheServerNeverReceivedAndDeletesItsFiles() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val objectIds = harness.rowsOf(entryId).objects.map { it.id }
        assertEquals(2, objectIds.size)

        harness.service.deletePermanently(listOf(entryId))

        assertEquals(0, harness.outbox().size)
        val after = harness.rowsOf(entryId)
        assertNull(after.entry)
        assertEquals(0, after.memberships.size)
        assertEquals(0, after.media.size)
        assertEquals(0, after.objects.size)
        for (objectId in objectIds) assertNull(harness.fixture.fileStore.size(objectId))
        assertEquals(listOf(entryId), harness.changed.last())
    }

    @Test
    fun queuesOneDeleteEntryForASyncedEntryAndRemovesRowsAndFiles() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val objectIds = harness.rowsOf(entryId).objects.map { it.id }
        harness.markSynced(entryId)
        harness.service.edit(entryId, EntryEdit(note = "queued edit"))

        harness.service.deletePermanently(listOf(entryId))

        val items = harness.outbox()
        assertEquals(listOf("deleteEntry"), items.map { it.kind })
        assertEquals(entryPayload(entryId), items[0].payload)
        val after = harness.rowsOf(entryId)
        assertNull(after.entry)
        assertEquals(0, after.media.size)
        assertEquals(0, after.objects.size)
        for (objectId in objectIds) assertNull(harness.fixture.fileStore.size(objectId))
    }

    @Test
    fun queuesDeleteEntryForAnEntryThatIsPendingCreateButAlreadyPosted() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.fixture.rowStore.transaction { tx ->
            tx.entries.get(entryId)?.let { tx.entries.put(it.copy(baseRevision = 1, baseMetadataBlob = it.metadataBlob)) }
        }
        assertEquals(EntryLocalState.PENDING_CREATE, harness.entryRow(entryId).localState)

        harness.service.deletePermanently(listOf(entryId))

        assertEquals(listOf("deleteEntry"), harness.outboxKinds())
    }

    @Test
    fun queuesDeleteEntryWhenTheCreateEntryItemWasAlreadyAttempted() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.setAttempts("createEntry", 1)
        harness.service.deletePermanently(listOf(entryId))
        assertEquals(listOf("deleteEntry"), harness.outboxKinds())
    }

    @Test
    fun queuesDeleteEntryWhenTheEntryIsInFlight() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.hooks.inFlight += entryId
        harness.service.deletePermanently(listOf(entryId))
        assertEquals(listOf("deleteEntry"), harness.outboxKinds())
    }

    @Test
    fun wipesTheCachedEntryAndMediaKeys() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val mediaId = harness.rowsOf(entryId).media[0].id
        val entryKey = harness.entryKeys.entryKey(entryId)
        val mediaKey = harness.entryKeys.mediaKey(entryId, mediaId)
        harness.service.deletePermanently(listOf(entryId))
        assertTrue(isWiped(entryKey))
        assertTrue(isWiped(mediaKey))
    }

    @Test
    fun deletesThePlainThumbnailFilesToo() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val mediaId = harness.rowsOf(entryId).media[0].id
        harness.fixture.fileStore.writePlainThumbnail(mediaId, byteArrayOf(1))
        harness.service.deletePermanently(listOf(entryId))
        assertNull(harness.fixture.fileStore.plainThumbnailFile(mediaId))
    }

    @Test
    fun deletePermanentlySkipsEntriesThatAreNotOwned() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.setOwner(entryId, OTHER_USER)
        harness.service.deletePermanently(listOf(entryId))
        assertNotNull(harness.rowsOf(entryId).entry)
    }
}
