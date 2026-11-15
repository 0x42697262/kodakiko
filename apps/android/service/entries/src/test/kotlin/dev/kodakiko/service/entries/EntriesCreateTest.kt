package dev.kodakiko.service.entries

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.media.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

const val PHOTO_BYTES = 300
val EARLIER_TIME = EntryTimeEdit(takenAtUtc = "2026-05-01T08:00:00.000Z", utcOffsetMinutes = 120)

fun parisPhoto(): ByteArray =
    buildJpeg(
        segments = listOf(jfifApp0(), exifApp1(buildTiff(dateTimeOriginal = "2026:05:01 10:00:00", offsetTimeOriginal = "+02:00", gps = PARIS))),
        scanBytes = 256,
    )

fun emptyInput(time: EntryTimeEdit? = null, location: OptionalValue<EntryLocation?> = OptionalValue.Absent): CreateEntryInput =
    CreateEntryInput(note = "", tags = emptyList(), time = time, location = location)

@RunWith(RobolectricTestRunner::class)
class EntriesCreateTest : EntriesTestBase() {
    @Test
    fun writesExactlyOneEntryOneMembershipNMediaTwoNObjectsAndOneCreateEntryItem() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES), fakeJpeg(PHOTO_BYTES, 9)), note = "Picnic", tags = listOf("family", "beach"))

        val rows = harness.rowsOf(entryId)
        val entry = rows.entry
        assertNotNull(entry)
        assertEquals(harness.fixture.userId, entry?.ownerId)
        assertEquals(EntryPurpose.JOURNAL, entry?.purpose)
        assertEquals(1L, entry?.revision)
        assertNull(entry?.baseMetadataBlob)
        assertEquals(0L, entry?.baseRevision)
        assertNull(entry?.serverPendingBlob)
        assertNull(entry?.serverPendingRevision)
        assertEquals(EntryLocalState.PENDING_CREATE, entry?.localState)
        assertNull(entry?.trashedAt)
        assertEquals(FIRST_NOW_ISO, entry?.createdAt)
        assertEquals(FIRST_NOW_ISO, entry?.updatedAt)
        assertEquals(1, rows.memberships.size)
        assertEquals(harness.fixture.homeCollectionId, rows.memberships[0].collectionId)
        assertEquals(0L, rows.memberships[0].seq)
        assertEquals(2, rows.media.size)
        assertEquals(4, rows.objects.size)
        for (row in rows.objects) {
            assertEquals(ObjectLocalState.PRESENT, row.localState)
            assertEquals(ObjectUploadState.LOCAL, row.uploadState)
            assertNull(row.uploadJobId)
            assertEquals(FIRST_NOW_MS, row.lastAccessAt)
            assertEquals(row.sizeBytes, harness.fixture.fileStore.size(row.id))
            assertTrue(row.plaintextSize > 0)
        }
        val items = harness.outbox()
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("createEntry", item.kind)
        assertEquals(entryPayload(entryId), item.payload)
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals(0L, item.attempts)
        assertNull(item.lastErrorCode)
    }

    @Test
    fun sealsMetadataThatOpensWithTheEntryKeyFromTheMembershipWrap() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), note = "Picnic", tags = listOf("b", "a"))
        val rows = harness.rowsOf(entryId)
        val entryKey = unwrapKey(
            sodium,
            harness.fixture.homeCollectionKey,
            rows.memberships[0].entryKeyWrap,
            AdContext.ENTRY_KEY_BY_COLLECTION,
            "${harness.fixture.homeCollectionId}/$entryId",
        )
        val metadata = openMetadataForTest(sodium, entryKey, entryId, rows.entry?.metadataBlob ?: ByteArray(0))
        assertEquals("Picnic", metadata.note)
        assertEquals(listOf("a", "b"), metadata.tags)
        assertEquals(FIRST_NOW_ISO, metadata.createdAtUtc)
        assertEquals(FIRST_NOW_ISO, metadata.editedAtUtc)
        assertEquals(TEST_TIME_ZONE, metadata.timeZone)
        assertEquals(rows.media.map { it.id }.sorted(), metadata.media.map { it.mediaId }.sorted())
        for (media in rows.media) {
            val mediaKey = unwrapKey(sodium, entryKey, media.mediaKeyWrap, AdContext.MEDIA_KEY_BY_ENTRY, "$entryId/${media.id}")
            assertEquals(32, mediaKey.size)
        }
    }

    @Test
    fun clearsTheTrayWithoutDeletingTheFilesAndNotifies() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        assertEquals(0, harness.tray.items.size)
        for (row in harness.rowsOf(entryId).objects) assertNotNull(harness.fixture.fileStore.size(row.id))
        assertEquals(1, harness.notified.get())
        assertEquals(listOf(listOf(entryId)), harness.changed)
    }

    @Test
    fun keepsTheTrayOrderAsTheMetadataMediaOrder() = runTest {
        val harness = harness()
        val first = harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1))
        val second = harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2))
        harness.tray.reorder(1, 0)
        val entryId = harness.service.createFromTray(emptyInput())
        assertEquals(listOf(second, first), harness.metadataOf(entryId).media.map { it.mediaId })
    }

    @Test
    fun usesTheTimeGivenByTheUserInsteadOfTheComputedOne() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES))
        val entryId = harness.service.createFromTray(emptyInput(time = EARLIER_TIME))
        val metadata = harness.metadataOf(entryId)
        assertEquals(EARLIER_TIME.takenAtUtc, metadata.takenAtUtc)
        assertEquals(EARLIER_TIME.utcOffsetMinutes, metadata.utcOffsetMinutes)
    }

    @Test
    fun failsWithTheLockedErrorWhenTheJournalIsLocked() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES))
        harness.lock()
        expectFailure<AccountLockedException> { harness.service.createFromTray(emptyInput()) }
        assertEquals(0, harness.outbox().size)
    }

    @Test
    fun usesALocationGivenByTheUserAndRemovesItWhenGivenNull() = runTest {
        val harness = harness()
        val photo = parisPhoto()
        harness.importIntoTray(photo)
        val removed = harness.service.createFromTray(emptyInput(location = OptionalValue.Present(null)))
        assertNull(harness.metadataOf(removed).location)

        harness.importIntoTray(photo)
        val chosen = EntryLocation(latitude = 1.25, longitude = 2.5, accuracyMeters = 10.0, source = LocationSource.DEVICE)
        val placed = harness.service.createFromTray(emptyInput(location = OptionalValue.Present(chosen)))
        assertEquals(chosen, harness.metadataOf(placed).location)
    }

    @Test
    fun refusesWhenTheTrayIsClearedWhileItResolvesKeysAndWritesNothing() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES))
        harness.hooks.beforeKey = {
            harness.hooks.beforeKey = null
            harness.tray.clear(true)
        }
        expectFailure<TrayChangedException> { harness.service.createFromTray(emptyInput()) }
        assertEquals(0, harness.outbox().size)
        assertEquals(0, harness.entryCount())
    }

    @Test
    fun refusesWhenAnItemJoinsTheTrayWhileItResolvesKeys() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1))
        val late = fakeJpeg(PHOTO_BYTES, 2)
        harness.hooks.beforeKey = {
            harness.hooks.beforeKey = null
            harness.importIntoTray(late)
        }
        expectFailure<TrayChangedException> { harness.service.createFromTray(emptyInput()) }
        assertEquals(2, harness.tray.items.size)
        assertEquals(0, harness.outbox().size)
    }

    @Test
    fun putsTheItemsBackWithTheirFilesWhenTheWriteFails() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1))
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 2))
        val before = harness.tray.items.map { it.mediaId }
        harness.hooks.failEnqueue = true
        assertNotNull(failureOf { harness.service.createFromTray(emptyInput()) })
        assertEquals(before, harness.tray.items.map { it.mediaId })
        for (item in harness.tray.items) {
            assertNotNull(harness.fixture.fileStore.size(item.originalObjectId))
            assertFalse(isWiped(item.mediaKey))
        }
        assertEquals(0, harness.entryCount())
    }

    @Test
    fun refusesAnEmptyTray() = runTest {
        val harness = harness()
        expectFailure<EmptyTrayException> { harness.service.createFromTray(emptyInput()) }
        assertEquals(0, harness.outbox().size)
    }

    @Test
    fun b021TakesTheEntryTimeAndLocationFromAPhotoWithExifDateAndGps() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(parisPhoto()))
        val metadata = harness.metadataOf(entryId)
        assertEquals("2026-05-01T08:00:00.000Z", metadata.takenAtUtc)
        assertEquals(120L, metadata.utcOffsetMinutes)
        val location = metadata.location
        assertNotNull(location)
        assertNull(location?.accuracyMeters)
        assertEquals(LocationSource.EXIF, location?.source)
        assertEquals(PARIS.latitude, location?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(PARIS.longitude, location?.longitude ?: 0.0, COORDINATE_DELTA)
    }

    @Test
    fun b021FallsBackToTheDeviceLocationOfTheTraySourceDevice() = runTest {
        val harness = harness()
        harness.importIntoTray(fakeJpeg(PHOTO_BYTES))
        harness.tray.setDeviceLocation(DeviceLocation(GeoPoint(PARIS.latitude, PARIS.longitude), 25.0))
        val entryId = harness.service.createFromTray(emptyInput())
        assertEquals(EntryLocation(PARIS.latitude, PARIS.longitude, 25.0, LocationSource.DEVICE), harness.metadataOf(entryId).location)
    }

    @Test
    fun createFromMediaWritesAnEntryWithoutTouchingTheTrayAndWipesTheKeys() = runTest {
        val harness = harness()
        val trayItem = harness.importIntoTray(fakeJpeg(PHOTO_BYTES, 1))
        val outcome = harness.pipeline.importOne(harness.pickerInput(fakeJpeg(PHOTO_BYTES, 2)), ImportSlots(0, TEST_TRAY_LIMIT))
        val media = (outcome as ImportOutcome.Imported).media
        val entryId = harness.service.createFromMedia(CreateEntryInput(note = "restored", tags = emptyList(), time = null), listOf(media))
        assertEquals(listOf(media.mediaId), harness.metadataOf(entryId).media.map { it.mediaId })
        assertEquals("restored", harness.metadataOf(entryId).note)
        assertEquals(listOf(trayItem), harness.tray.items.map { it.mediaId })
        assertTrue(isWiped(media.mediaKey))
        assertEquals(listOf("createEntry"), harness.outboxKinds())
        assertEquals(listOf(listOf(entryId)), harness.changed)
        expectFailure<EmptyTrayException> { harness.service.createFromMedia(emptyInput(), emptyList()) }
    }
}
