package dev.kodakiko.service.entries

import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.InvalidEditError
import dev.kodakiko.js.JsDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

const val UNKNOWN_MEDIA_ID = "AAAAAAAAAAAAAAAAAAAAAA"

@RunWith(RobolectricTestRunner::class)
class EntriesEditTest : EntriesTestBase() {
    private fun later(steps: Long): String = JsDate.toIsoString((FIRST_NOW_MS + steps * STEP_MS).toDouble())

    @Test
    fun queuesOneUpdateEntryMetaForTwoOfflineEditsOfASyncedEntry() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "one")
        harness.markSynced(entryId)
        harness.clock.advance(STEP_MS)
        harness.service.edit(entryId, EntryEdit(note = "two"))
        harness.clock.advance(STEP_MS)
        harness.service.edit(entryId, EntryEdit(note = "three"))

        assertEquals(listOf("updateEntryMeta"), harness.outboxKinds())
        val row = harness.entryRow(entryId)
        assertEquals(EntryLocalState.PENDING_UPDATE, row.localState)
        assertEquals(later(2), row.updatedAt)
        assertEquals("three", harness.metadataOf(entryId).note)
    }

    @Test
    fun keepsPendingCreateAndQueuesOneUpdateEntryMetaBehindCreateEntry() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "one")
        harness.service.edit(entryId, EntryEdit(note = "two"))
        harness.service.edit(entryId, EntryEdit(tags = listOf("x")))

        assertEquals(listOf("createEntry", "updateEntryMeta"), harness.outboxKinds())
        assertEquals(EntryLocalState.PENDING_CREATE, harness.entryRow(entryId).localState)
    }

    @Test
    fun writesNothingForAnEquivalentEdit() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "same", tags = listOf("a"))
        harness.markSynced(entryId)
        val before = harness.entryRow(entryId)
        val notifiedBefore = harness.notified.get()
        val changedBefore = harness.changed.size
        harness.clock.advance(STEP_MS)

        harness.service.edit(entryId, EntryEdit(note = "same", tags = listOf("a")))

        val after = harness.entryRow(entryId)
        assertEquals(before.copy(metadataBlob = after.metadataBlob, baseMetadataBlob = after.baseMetadataBlob), after)
        assertEquals(before.metadataBlob.toList(), after.metadataBlob.toList())
        assertEquals(before.baseMetadataBlob?.toList(), after.baseMetadataBlob?.toList())
        assertEquals(0, harness.outbox().size)
        assertEquals(notifiedBefore, harness.notified.get())
        assertEquals(changedBefore, harness.changed.size)
    }

    @Test
    fun stampsEditedAtUtcAndNotifiesOnARealEdit() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.clock.advance(STEP_MS)
        harness.service.edit(entryId, EntryEdit(note = "changed"))
        val metadata = harness.metadataOf(entryId)
        assertEquals("changed", metadata.note)
        assertEquals(FIRST_NOW_ISO, metadata.createdAtUtc)
        assertEquals(later(1), metadata.editedAtUtc)
        assertEquals(listOf(entryId), harness.changed.last())
    }

    @Test
    fun refusesAViewer() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.setOwner(entryId, OTHER_USER)
        expectFailure<ReadOnlyEntryException> { harness.service.edit(entryId, EntryEdit(note = "nope")) }
        assertEquals(listOf("createEntry"), harness.outboxKinds())
    }

    @Test
    fun refusesAnEntryWhoseOnlyMembershipIsInASharedCollectionWithRoleViewer() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val sharedId = harness.fixture.randomId()
        harness.fixture.rowStore.transaction { tx ->
            val row = tx.entries.get(entryId) ?: error("fixture entry missing")
            val membership = tx.memberships.byEntry(entryId).firstOrNull() ?: error("fixture entry missing")
            tx.entries.put(row.copy(ownerId = OTHER_USER, localState = EntryLocalState.SYNCED))
            tx.memberships.delete(membership.collectionId, entryId)
            tx.collections.put(collectionRow(sharedId, CollectionKind.SHARED, OTHER_USER, CollectionRole.VIEWER, byteArrayOf(1)))
            tx.memberships.put(membership.copy(collectionId = sharedId))
        }
        expectFailure<ReadOnlyEntryException> { harness.service.edit(entryId, EntryEdit(note = "nope")) }
        expectFailure<ReadOnlyEntryException> { harness.service.removeMedia(entryId, UNKNOWN_MEDIA_ID) }
        expectFailure<ReadOnlyEntryException> { harness.service.addMedia(entryId, emptyList()) }
    }

    @Test
    fun refusesAddMediaPassedToEdit() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES), fakeJpeg(PHOTO_BYTES, 2)))
        val descriptor = harness.metadataOf(entryId).media[0]
        val change = EntryEdit(addMedia = listOf(descriptor.copy(mediaId = UNKNOWN_MEDIA_ID)))
        expectFailure<InvalidEditError> { harness.service.edit(entryId, change) }
        assertEquals(2, harness.metadataOf(entryId).media.size)
        assertEquals(listOf("createEntry"), harness.outboxKinds())
    }

    @Test
    fun refusesRemoveMediaIdPassedToEdit() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES), fakeJpeg(PHOTO_BYTES, 2)))
        val mediaId = harness.rowsOf(entryId).media[0].id
        expectFailure<InvalidEditError> { harness.service.edit(entryId, EntryEdit(removeMediaId = mediaId)) }
        assertEquals(2, harness.metadataOf(entryId).media.size)
        assertEquals(listOf("createEntry"), harness.outboxKinds())
    }

    @Test
    fun reReadsAndKeepsAChangeThatLandedBetweenItsReadAndItsWrite() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "base")
        harness.markSynced(entryId)
        harness.hooks.beforeKey = {
            harness.hooks.beforeKey = null
            harness.writeConcurrentNote(entryId, "from elsewhere")
        }
        harness.service.edit(entryId, EntryEdit(tags = listOf("x")))
        val metadata = harness.metadataOf(entryId)
        assertEquals("from elsewhere", metadata.note)
        assertEquals(listOf("x"), metadata.tags)
    }

    @Test
    fun givesUpAfterEditRetryAttemptsWhenTheEntryKeepsChanging() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "base")
        harness.markSynced(entryId)
        var round = 0
        harness.hooks.beforeKey = {
            round += 1
            harness.writeConcurrentNote(entryId, "change $round")
        }
        expectFailure<EntryChangedException> { harness.service.edit(entryId, EntryEdit(tags = listOf("x"))) }
        assertEquals(5, EntriesConstants.EDIT_RETRY_ATTEMPTS)
        assertEquals(EntriesConstants.EDIT_RETRY_ATTEMPTS, round)
        harness.hooks.beforeKey = null
        assertEquals(emptyList<String>(), harness.metadataOf(entryId).tags)
    }

    @Test
    fun removeMediaAndAddMediaAlsoKeepAConcurrentChange() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)))
        harness.markSynced(entryId)
        val mediaId = harness.rowsOf(entryId).media[0].id
        harness.hooks.beforeKey = {
            harness.hooks.beforeKey = null
            harness.writeConcurrentNote(entryId, "kept 1")
        }
        harness.service.removeMedia(entryId, mediaId)
        assertEquals("kept 1", harness.metadataOf(entryId).note)
        assertEquals(1, harness.metadataOf(entryId).media.size)

        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 3))
        harness.hooks.beforeKey = {
            harness.hooks.beforeKey = null
            harness.writeConcurrentNote(entryId, "kept 2")
        }
        harness.service.addMedia(entryId, harness.tray.items.toList())
        assertEquals("kept 2", harness.metadataOf(entryId).note)
        assertEquals(2, harness.metadataOf(entryId).media.size)
    }

    @Test
    fun refusesAnInvalidEditOfTheMediaOrder() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        expectFailure<InvalidEditError> { harness.service.edit(entryId, EntryEdit(mediaOrder = listOf(UNKNOWN_MEDIA_ID))) }
    }

    @Test
    fun failsWithTheLockedErrorWhenTheJournalIsLocked() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.lock()
        expectFailure<AccountLockedException> { harness.service.edit(entryId, EntryEdit(note = "x")) }
    }

    @Test
    fun setsATrimmedCaptionAndClearsItWithAnEmptyCaption() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)))
        harness.markSynced(entryId)
        val (first, second) = harness.metadataOf(entryId).media.map { it.mediaId }

        harness.service.edit(entryId, EntryEdit(captions = mapOf(second to "  Sunset  ")))

        assertEquals(listOf(null, "Sunset"), harness.metadataOf(entryId).media.map { it.caption })
        assertEquals(listOf("updateEntryMeta"), harness.outboxKinds())
        assertEquals(EntryLocalState.PENDING_UPDATE, harness.entryRow(entryId).localState)

        harness.service.edit(entryId, EntryEdit(captions = mapOf(second to "")))

        assertEquals(listOf(first, second), harness.metadataOf(entryId).media.map { it.mediaId })
        assertNull(harness.metadataOf(entryId).media[1].caption)
        assertEquals(listOf("updateEntryMeta"), harness.outboxKinds())
        assertEquals(listOf(listOf(entryId), listOf(entryId)), harness.changed.takeLast(2))
    }

    @Test
    fun setsTheCoverByMovingTheItemFirstWithOneQueuedUpdate() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2), fakeJpeg(PHOTO_BYTES, 3)))
        harness.markSynced(entryId)
        val (first, second, third) = harness.metadataOf(entryId).media.map { it.mediaId }

        harness.service.edit(entryId, EntryEdit(mediaOrder = listOf(third, first, second)))

        assertEquals(listOf(third, first, second), harness.metadataOf(entryId).media.map { it.mediaId })
        assertEquals(listOf("updateEntryMeta"), harness.outboxKinds())
        assertEquals(3, harness.rowsOf(entryId).media.size)
    }
}
