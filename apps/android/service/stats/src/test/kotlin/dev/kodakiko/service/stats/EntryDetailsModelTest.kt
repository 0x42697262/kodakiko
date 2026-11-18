package dev.kodakiko.service.stats

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

val DETAILS_VIDEO = MediaDescriptorV1(
    mediaId = "MEDIA1",
    kind = MediaKind.VIDEO,
    mimeType = "video/mp4",
    width = 1920,
    height = 1080,
    durationMs = 20_500,
    originalPlaintextBytes = 5_242_880,
    thumbnail = ThumbnailDescriptor(320, 180, 9000),
    capturedAtUtc = "2026-11-01T07:59:00.000Z",
    originalFileName = "clip.mp4",
)

val DETAILS_METADATA = EntryMetadataV1(
    takenAtUtc = "2026-11-01T08:00:00.000Z",
    utcOffsetMinutes = 60,
    timeZone = "Europe/Berlin",
    note = "hello",
    tags = listOf("beach"),
    location = EntryLocation(52.52, 13.405, null, LocationSource.EXIF),
    media = listOf(DETAILS_VIDEO),
    createdAtUtc = "2026-11-01T08:00:00.000Z",
    editedAtUtc = "2026-11-01T08:00:00.000Z",
)

fun detailsEntryRow(id: String = "ENTRY1", revision: Long = 3, localState: EntryLocalState = EntryLocalState.SYNCED): EntryRow =
    EntryRow(
        id = id,
        ownerId = "owner",
        purpose = EntryPurpose.JOURNAL,
        revision = revision,
        metadataBlob = byteArrayOf(1),
        baseMetadataBlob = null,
        baseRevision = null,
        serverPendingBlob = null,
        serverPendingRevision = null,
        trashedAt = null,
        createdAt = "2026-11-01T08:01:00.000Z",
        updatedAt = "2026-11-02T10:00:00.000Z",
        localState = localState,
    )

fun detailsObjectRow(
    id: String,
    mediaId: String,
    variant: ObjectVariant,
    sizeBytes: Long,
    localState: ObjectLocalState,
    uploadState: ObjectUploadState,
): ObjectRow = ObjectRow(id, mediaId, variant, sizeBytes, sizeBytes, localState, uploadState, null, 0)

class EntryDetailsModelTest {
    private val sections: List<DetailSection> = entryDetailSections(
        entryId = "ENTRY1",
        metadata = DETAILS_METADATA,
        row = detailsEntryRow(),
        objects = mapOf("MEDIA1" to listOf(detailsObjectRow("OBJ1", "MEDIA1", ObjectVariant.ORIGINAL, 5_300_000, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED))),
        technical = true,
    )

    @Test
    fun listsTheEntryFactsWithTheOffsetAndTheLocationSource() {
        val entry = sections[0]
        assertEquals(DetailSectionKind.ENTRY, entry.kind)
        assertEquals(null, entry.position)
        assertEquals(
            listOf(
                DetailRow(DetailLabel.ID, DetailValue.Text("ENTRY1")),
                DetailRow(DetailLabel.TAKEN, DetailValue.Text("2026-11-01 09:00 (UTC+01:00, Europe/Berlin)")),
                DetailRow(DetailLabel.CREATED, DetailValue.Text("2026-11-01 08:01 UTC")),
                DetailRow(DetailLabel.EDITED, DetailValue.Text("2026-11-02 10:00 UTC")),
                DetailRow(DetailLabel.VERSION, DetailValue.Text("3")),
                DetailRow(DetailLabel.SYNC, DetailValue.Text("synced")),
                DetailRow(DetailLabel.LOCATION, DetailValue.Text("52.52000, 13.40500 (exif)")),
                DetailRow(DetailLabel.TAGS, DetailValue.Text("#beach")),
                DetailRow(DetailLabel.NOTE_LENGTH, DetailValue.Characters(5)),
            ),
            entry.rows,
        )
    }

    @Test
    fun listsEachMediaItemWithTypeSizeDimensionsDurationAndStorageState() {
        assertEquals(2, sections.size)
        val media = sections[1]
        assertEquals(DetailSectionKind.VIDEO, media.kind)
        assertEquals(1, media.position)
        assertEquals(
            listOf(
                DetailRow(DetailLabel.FILE_NAME, DetailValue.Text("clip.mp4")),
                DetailRow(DetailLabel.TYPE, DetailValue.Text("video/mp4")),
                DetailRow(DetailLabel.SIZE, DetailValue.Bytes(5_242_880)),
                DetailRow(DetailLabel.DIMENSIONS, DetailValue.Text("1920 × 1080")),
                DetailRow(DetailLabel.DURATION, DetailValue.Duration(20_500)),
                DetailRow(DetailLabel.CAPTURED_AT, DetailValue.Text("2026-11-01 07:59 UTC")),
                DetailRow(DetailLabel.ORIGINAL, DetailValue.Storage(onDevice = true, uploaded = true)),
                DetailRow(DetailLabel.MEDIA_ID, DetailValue.Text("MEDIA1")),
                DetailRow(DetailLabel.ENCRYPTED_SIZE, DetailValue.Bytes(5_300_000)),
            ),
            media.rows,
        )
    }

    @Test
    fun hidesIdsAndEncryptedSizesUnlessTechnicalDetailsAreOn() {
        val plain = entryDetailSections(
            entryId = "ENTRY1",
            metadata = DETAILS_METADATA,
            row = detailsEntryRow(),
            objects = emptyMap(),
            technical = false,
        )
        assertFalse(plain.flatMap { it.rows }.any { it.label == DetailLabel.ID })
        assertFalse(plain.flatMap { it.rows }.any { it.label == DetailLabel.MEDIA_ID || it.label == DetailLabel.ENCRYPTED_SIZE })
        assertEquals(DetailRow(DetailLabel.ORIGINAL, DetailValue.Unknown), plain[1].rows.last())
    }

    @Test
    fun aNegativeOffsetWithoutTimeZoneNoRowNoLocationAndNoTagsLeavesOnlyTimeAndNoteLength() {
        val sparse = entryDetailSections(
            entryId = "ENTRY2",
            metadata = DETAILS_METADATA.copy(
                takenAtUtc = "2026-03-01T02:10:00.000Z",
                utcOffsetMinutes = -330,
                timeZone = null,
                note = "a😀b",
                tags = emptyList(),
                location = null,
                media = emptyList(),
            ),
            row = null,
            objects = emptyMap(),
            technical = false,
        )
        assertEquals(1, sparse.size)
        assertEquals(
            listOf(
                DetailRow(DetailLabel.TAKEN, DetailValue.Text("2026-02-28 20:40 (UTC-05:30)")),
                DetailRow(DetailLabel.NOTE_LENGTH, DetailValue.Characters(4)),
            ),
            sparse[0].rows,
        )
    }

    @Test
    fun severalTagsAreHashPrefixedAndDeviceLocationsKeepTheirSource() {
        val tagged = entryDetailSections(
            entryId = "ENTRY3",
            metadata = DETAILS_METADATA.copy(
                tags = listOf("beach", "family trip"),
                location = EntryLocation(-33.8688, 151.2093, 12.0, LocationSource.DEVICE),
                media = emptyList(),
            ),
            row = detailsEntryRow(id = "ENTRY3", revision = 1, localState = EntryLocalState.PENDING_UPDATE),
            objects = emptyMap(),
            technical = false,
        )
        val rows = tagged[0].rows.associate { it.label to it.value }
        assertEquals(DetailValue.Text("#beach #family trip"), rows[DetailLabel.TAGS])
        assertEquals(DetailValue.Text("-33.86880, 151.20930 (device)"), rows[DetailLabel.LOCATION])
        assertEquals(DetailValue.Text("1"), rows[DetailLabel.VERSION])
        assertEquals(DetailValue.Text("pending_update"), rows[DetailLabel.SYNC])
    }

    @Test
    fun photosAreNumberedByPositionAndOptionalFactsAreLeftOut() {
        val photo = DETAILS_VIDEO.copy(
            mediaId = "MEDIA2",
            kind = MediaKind.PHOTO,
            mimeType = "image/jpeg",
            width = 4000,
            height = 3000,
            durationMs = null,
            originalPlaintextBytes = 2_000_000,
            capturedAtUtc = null,
            originalFileName = null,
        )
        val sections = entryDetailSections(
            entryId = "ENTRY1",
            metadata = DETAILS_METADATA.copy(media = listOf(DETAILS_VIDEO, photo)),
            row = detailsEntryRow(),
            objects = mapOf(
                "MEDIA2" to listOf(
                    detailsObjectRow("THUMB2", "MEDIA2", ObjectVariant.THUMBNAIL, 900, ObjectLocalState.PRESENT, ObjectUploadState.LOCAL),
                    detailsObjectRow("OBJ2", "MEDIA2", ObjectVariant.ORIGINAL, 2_000_100, ObjectLocalState.MISSING, ObjectUploadState.UPLOADED),
                ),
            ),
            technical = true,
        )
        assertEquals(3, sections.size)
        assertEquals(DetailSectionKind.VIDEO, sections[1].kind)
        assertEquals(1, sections[1].position)
        assertEquals(DetailRow(DetailLabel.ORIGINAL, DetailValue.Unknown), sections[1].rows.first { it.label == DetailLabel.ORIGINAL })
        assertFalse(sections[1].rows.any { it.label == DetailLabel.ENCRYPTED_SIZE })
        assertEquals(DetailSectionKind.PHOTO, sections[2].kind)
        assertEquals(2, sections[2].position)
        assertEquals(
            listOf(
                DetailRow(DetailLabel.TYPE, DetailValue.Text("image/jpeg")),
                DetailRow(DetailLabel.SIZE, DetailValue.Bytes(2_000_000)),
                DetailRow(DetailLabel.DIMENSIONS, DetailValue.Text("4000 × 3000")),
                DetailRow(DetailLabel.ORIGINAL, DetailValue.Storage(onDevice = false, uploaded = true)),
                DetailRow(DetailLabel.THUMBNAIL, DetailValue.Storage(onDevice = true, uploaded = false)),
                DetailRow(DetailLabel.MEDIA_ID, DetailValue.Text("MEDIA2")),
                DetailRow(DetailLabel.ENCRYPTED_SIZE, DetailValue.Bytes(2_000_100)),
            ),
            sections[2].rows,
        )
    }

    @Test
    fun onlyAnUploadedObjectCountsAsUploadedAndOnlyAPresentOneAsOnTheDevice() {
        val states = listOf(
            ObjectLocalState.UNAVAILABLE to ObjectUploadState.DEAD,
            ObjectLocalState.PRESENT to ObjectUploadState.QUEUED,
            ObjectLocalState.PRESENT to ObjectUploadState.UPLOADING,
        )
        val values = states.map { (local, upload) ->
            entryDetailSections(
                entryId = "ENTRY1",
                metadata = DETAILS_METADATA,
                row = null,
                objects = mapOf("MEDIA1" to listOf(detailsObjectRow("OBJ1", "MEDIA1", ObjectVariant.ORIGINAL, 1, local, upload))),
                technical = false,
            )[1].rows.single { it.label == DetailLabel.ORIGINAL }.value
        }
        assertEquals(
            listOf(
                DetailValue.Storage(onDevice = false, uploaded = false),
                DetailValue.Storage(onDevice = true, uploaded = false),
                DetailValue.Storage(onDevice = true, uploaded = false),
            ),
            values,
        )
    }
}
