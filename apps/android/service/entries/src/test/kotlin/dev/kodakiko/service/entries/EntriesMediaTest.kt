package dev.kodakiko.service.entries

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.domain.InvalidEditError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntriesMediaTest : EntriesTestBase() {
    @Test
    fun b027KeepsTwoPhotosWhenOneOfThreeIsRemoved() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2), fakeJpeg(PHOTO_BYTES, 3)))
        val before = harness.rowsOf(entryId)
        val removedId = before.media[1].id
        val removedObjects = before.objects.filter { it.mediaId == removedId }
        harness.markSynced(entryId)

        harness.service.removeMedia(entryId, removedId)

        val after = harness.rowsOf(entryId)
        assertFalse(removedId in after.media.map { it.id })
        assertEquals(2, after.media.size)
        assertEquals(2, harness.metadataOf(entryId).media.size)
        assertEquals(2, removedObjects.size)
        for (row in removedObjects) assertNull(harness.fixture.fileStore.size(row.id))
        val items = harness.outbox()
        assertEquals(listOf("updateEntryMeta", "removeMedia"), items.map { it.kind })
        assertEquals(entryMediaPayload(entryId, removedId), items[1].payload)
    }

    @Test
    fun wipesTheCachedKeyOfTheRemovedMediaOnly() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)))
        val (removed, kept) = harness.rowsOf(entryId).media.map { it.id }
        val removedKey = harness.entryKeys.mediaKey(entryId, removed)
        val keptKey = harness.entryKeys.mediaKey(entryId, kept)
        harness.service.removeMedia(entryId, removed)
        assertTrue(isWiped(removedKey))
        assertFalse(isWiped(keptKey))
    }

    @Test
    fun b027RefusesToRemoveTheLastMediaItem() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val mediaId = harness.rowsOf(entryId).media[0].id
        expectFailure<InvalidEditError> { harness.service.removeMedia(entryId, mediaId) }
        val after = harness.rowsOf(entryId)
        assertEquals(1, after.media.size)
        assertEquals(2, after.objects.size)
        assertEquals(listOf("createEntry"), harness.outboxKinds())
    }

    @Test
    fun removeMediaAlsoDeletesThePlainThumbnail() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)))
        val removed = harness.rowsOf(entryId).media[0].id
        harness.fixture.fileStore.writePlainThumbnail(removed, byteArrayOf(1))
        harness.service.removeMedia(entryId, removed)
        assertNull(harness.fixture.fileStore.plainThumbnailFile(removed))
        assertEquals(listOf(entryId), harness.changed.last())
    }

    @Test
    fun queuesAddMediaItemsAndThenOneUpdateEntryMeta() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1)))
        harness.markSynced(entryId)
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2))
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 3))
        val items = harness.tray.items.toList()

        harness.service.addMedia(entryId, items)

        val queued = harness.outbox()
        assertEquals(listOf("addMedia", "addMedia", "updateEntryMeta"), queued.map { it.kind })
        assertEquals(items.map { entryMediaPayload(entryId, it.mediaId) }, queued.take(2).map { it.payload })
        val rows = harness.rowsOf(entryId)
        assertEquals(3, rows.media.size)
        assertEquals(6, rows.objects.size)
        assertEquals(items.map { it.mediaId }, harness.metadataOf(entryId).media.map { it.mediaId }.drop(1))
        assertEquals(EntryLocalState.PENDING_UPDATE, harness.entryRow(entryId).localState)
    }

    @Test
    fun wrapsEachNewMediaKeyWithTheEntryKeyAndWipesTheTrayCopyAfterCommit() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1)))
        val mediaId = harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2))
        val items = harness.tray.items.toList()
        val keyCopy = items[0].mediaKey.copyOf()

        harness.service.addMedia(entryId, items)

        val row = harness.rowsOf(entryId).media.first { it.id == mediaId }
        val entryKey = harness.entryKeys.entryKey(entryId)
        val unwrapped = unwrapKey(sodium, entryKey, row.mediaKeyWrap, AdContext.MEDIA_KEY_BY_ENTRY, "$entryId/$mediaId")
        assertArrayEquals(keyCopy, unwrapped)
        assertTrue(isWiped(items[0].mediaKey))
    }

    @Test
    fun refusesAnEmptyListOfMedia() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1)))
        expectFailure<InvalidEditError> { harness.service.addMedia(entryId, emptyList()) }
        assertEquals(listOf("createEntry"), harness.outboxKinds())
    }

    @Test
    fun refusesMediaBeyondMaxMediaPerEntry() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)))
        harness.hooks.maxMediaPerEntry = 3
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 3))
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 4))
        expectFailure<InvalidEditError> { harness.service.addMedia(entryId, harness.tray.items.toList()) }
        assertEquals(2, harness.rowsOf(entryId).media.size)
        harness.service.addMedia(entryId, harness.tray.items.take(1))
        assertEquals(3, harness.rowsOf(entryId).media.size)
    }

    @Test
    fun readMetadataOpensTheLocalBlobOfAnEntry() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "hello")
        assertEquals("hello", harness.metadataOf(entryId).note)
    }

    @Test
    fun readMetadataRefusesAnEntryThatIsNotOnThisDevice() = runTest {
        val harness = harness()
        expectFailure<EntryNotFoundException> { harness.service.readMetadata(MISSING_ENTRY_ID) }
    }
}
