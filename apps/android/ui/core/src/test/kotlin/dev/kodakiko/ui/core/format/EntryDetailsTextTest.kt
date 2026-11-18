package dev.kodakiko.ui.core.format

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
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
import dev.kodakiko.service.stats.DetailSection
import dev.kodakiko.service.stats.DetailValue
import dev.kodakiko.service.stats.entryDetailSections
import dev.kodakiko.ui.core.format.DeviceSettingsMatrix.assertEverywhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val VIDEO = MediaDescriptorV1(
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

private val METADATA = EntryMetadataV1(
    takenAtUtc = "2026-11-01T08:00:00.000Z",
    utcOffsetMinutes = 60,
    timeZone = "Europe/Berlin",
    note = "hello",
    tags = listOf("beach"),
    location = EntryLocation(52.52, 13.405, null, LocationSource.EXIF),
    media = listOf(VIDEO),
    createdAtUtc = "2026-11-01T08:00:00.000Z",
    editedAtUtc = "2026-11-01T08:00:00.000Z",
)

private val ROW = EntryRow(
    id = "ENTRY1",
    ownerId = "owner",
    purpose = EntryPurpose.JOURNAL,
    revision = 3,
    metadataBlob = byteArrayOf(1),
    baseMetadataBlob = null,
    baseRevision = null,
    serverPendingBlob = null,
    serverPendingRevision = null,
    trashedAt = null,
    createdAt = "2026-11-01T08:01:00.000Z",
    updatedAt = "2026-11-02T10:00:00.000Z",
    localState = EntryLocalState.SYNCED,
)

private fun stored(id: String, mediaId: String, variant: ObjectVariant, sizeBytes: Long, local: ObjectLocalState, upload: ObjectUploadState) =
    ObjectRow(id, mediaId, variant, sizeBytes, sizeBytes, local, upload, null, 0)

@RunWith(RobolectricTestRunner::class)
class EntryDetailsTextTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val sections = entryDetailSections(
        entryId = "ENTRY1",
        metadata = METADATA,
        row = ROW,
        objects = mapOf("MEDIA1" to listOf(stored("OBJ1", "MEDIA1", ObjectVariant.ORIGINAL, 5_300_000, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED))),
        technical = true,
    )

    private fun rows(section: DetailSection): List<Pair<String, String>> =
        section.rows.map { detailLabelText(resources, it.label) to detailValueText(resources, it.value) }

    private fun value(value: DetailValue, expected: String) =
        assertEverywhere(expected, "detailValueText($value)") { detailValueText(resources, value) }

    @Test
    fun listsTheEntryFactsWithTheOffsetAndTheLocationSource() {
        val entry = rows(sections[0])
        assertTrue(
            entry.containsAll(
                listOf(
                    "Id" to "ENTRY1",
                    "Taken" to "2026-11-01 09:00 (UTC+01:00, Europe/Berlin)",
                    "Version" to "3",
                    "Location" to "52.52000, 13.40500 (exif)",
                    "Tags" to "#beach",
                ),
            ),
        )
        assertEquals("Entry", detailSectionTitle(resources, sections[0]))
    }

    @Test
    fun listsEachMediaItemWithTypeSizeDimensionsDurationAndStorageState() {
        assertEquals("Video 1", detailSectionTitle(resources, sections[1]))
        assertTrue(
            rows(sections[1]).containsAll(
                listOf(
                    "File name" to "clip.mp4",
                    "Type" to "video/mp4",
                    "Size" to "5 MB",
                    "Dimensions" to "1920 × 1080",
                    "Duration" to "00:20",
                    "Original" to "On this device, uploaded",
                ),
            ),
        )
    }

    @Test
    fun hidesIdsAndEncryptedSizesUnlessTechnicalDetailsAreOn() {
        val plain = entryDetailSections(entryId = "ENTRY1", metadata = METADATA, row = ROW, objects = emptyMap(), technical = false)
        assertFalse(plain.flatMap { rows(it) }.any { it.first == "Id" })
    }

    @Test
    fun everySectionReadsExactlyAsV031() {
        assertEquals(
            listOf(
                "Id" to "ENTRY1",
                "Taken" to "2026-11-01 09:00 (UTC+01:00, Europe/Berlin)",
                "Created" to "2026-11-01 08:01 UTC",
                "Edited" to "2026-11-02 10:00 UTC",
                "Version" to "3",
                "Sync state" to "synced",
                "Location" to "52.52000, 13.40500 (exif)",
                "Tags" to "#beach",
                "Note length" to "5 characters",
            ),
            rows(sections[0]),
        )
        assertEquals(
            listOf(
                "File name" to "clip.mp4",
                "Type" to "video/mp4",
                "Size" to "5 MB",
                "Dimensions" to "1920 × 1080",
                "Duration" to "00:20",
                "Captured (from the file)" to "2026-11-01 07:59 UTC",
                "Original" to "On this device, uploaded",
                "Media id" to "MEDIA1",
                "Encrypted size" to "5.1 MB",
            ),
            rows(sections[1]),
        )
    }

    @Test
    fun photoSectionsThumbnailsAndUnknownStatesReadAsV031() {
        val photo = VIDEO.copy(kind = MediaKind.PHOTO, mimeType = "image/jpeg", durationMs = null, capturedAtUtc = null, originalFileName = null)
        val sections = entryDetailSections(
            entryId = "E2",
            metadata = METADATA.copy(media = listOf(photo, VIDEO.copy(mediaId = "MEDIA2"))),
            row = null,
            objects = mapOf(
                "MEDIA1" to listOf(
                    stored("OBJ1", "MEDIA1", ObjectVariant.ORIGINAL, 2_000_100, ObjectLocalState.MISSING, ObjectUploadState.UPLOADED),
                    stored("THUMB1", "MEDIA1", ObjectVariant.THUMBNAIL, 100, ObjectLocalState.PRESENT, ObjectUploadState.LOCAL),
                ),
            ),
            technical = true,
        )
        assertEquals(listOf("Entry", "Photo 1", "Video 2"), sections.map { detailSectionTitle(resources, it) })
        assertEquals(
            listOf(
                "Id" to "E2",
                "Taken" to "2026-11-01 09:00 (UTC+01:00, Europe/Berlin)",
                "Location" to "52.52000, 13.40500 (exif)",
                "Tags" to "#beach",
                "Note length" to "5 characters",
            ),
            rows(sections[0]),
        )
        assertEquals(
            listOf(
                "Type" to "image/jpeg",
                "Size" to "5 MB",
                "Dimensions" to "1920 × 1080",
                "Original" to "Not on this device, uploaded",
                "Thumbnail" to "On this device, not uploaded yet",
                "Media id" to "MEDIA1",
                "Encrypted size" to "1.9 MB",
            ),
            rows(sections[1]),
        )
        assertEquals(
            listOf(
                "File name" to "clip.mp4",
                "Type" to "video/mp4",
                "Size" to "5 MB",
                "Dimensions" to "1920 × 1080",
                "Duration" to "00:20",
                "Captured (from the file)" to "2026-11-01 07:59 UTC",
                "Original" to "Unknown",
                "Media id" to "MEDIA2",
            ),
            rows(sections[2]),
        )
    }

    @Test
    fun typedValuesResolveToTheV031Texts() {
        value(DetailValue.Text("video/mp4"), "video/mp4")
        value(DetailValue.Characters(5), "5 characters")
        value(DetailValue.Characters(1), "1 characters")
        value(DetailValue.Bytes(5_242_880), "5 MB")
        value(DetailValue.Bytes(1536), "1.5 KB")
        value(DetailValue.Duration(20_500), "00:20")
        value(DetailValue.Duration(605_000), "10:05")
        value(DetailValue.Storage(onDevice = true, uploaded = true), "On this device, uploaded")
        value(DetailValue.Storage(onDevice = true, uploaded = false), "On this device, not uploaded yet")
        value(DetailValue.Storage(onDevice = false, uploaded = true), "Not on this device, uploaded")
        value(DetailValue.Storage(onDevice = false, uploaded = false), "Not on this device, not uploaded yet")
        value(DetailValue.Unknown, "Unknown")
    }
}
