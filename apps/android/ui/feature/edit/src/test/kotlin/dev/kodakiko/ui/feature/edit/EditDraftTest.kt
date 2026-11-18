package dev.kodakiko.ui.feature.edit

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.domain.applyEntryEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditDraftTest {
    private val now = "2026-11-12T10:00:00.000Z"
    private val a = "a".repeat(22)
    private val b = "b".repeat(22)
    private val c = "c".repeat(22)

    private fun media(id: String): MediaDescriptorV1 = MediaDescriptorV1(
        mediaId = id,
        kind = MediaKind.PHOTO,
        mimeType = "image/jpeg",
        width = 4,
        height = 3,
        durationMs = null,
        originalPlaintextBytes = 100,
        thumbnail = ThumbnailDescriptor(4, 3, 10),
        capturedAtUtc = null,
        originalFileName = null,
    )

    private fun current(): EntryMetadataV1 = EntryMetadataV1(
        takenAtUtc = "2026-11-01T08:00:00.000Z",
        utcOffsetMinutes = 60,
        timeZone = null,
        note = "hello",
        tags = listOf("beach", "family"),
        location = EntryLocation(48.8584, 2.2945, null, LocationSource.EXIF),
        media = listOf(media(a), media(b), media(c)),
        createdAtUtc = "2026-11-01T08:00:00.000Z",
        editedAtUtc = "2026-11-01T08:00:00.000Z",
    )

    @Test
    fun theDraftCarriesEveryEditableField() {
        val draft = draftFromMetadata(current())
        assertEquals(listOf(a, b, c), draft.mediaIds)
        assertEquals("hello", draft.note)
        assertEquals(listOf("beach", "family"), draft.tags)
        assertEquals(EntryTimeEdit("2026-11-01T08:00:00.000Z", 60), draft.time)
        assertEquals(EntryLocation(48.8584, 2.2945, null, LocationSource.EXIF), draft.location)
        assertEquals(emptyMap<String, String>(), draft.captions)
    }

    @Test
    fun reportsNothingWhenTheDraftIsUnchanged() {
        val meta = current()
        val diff = diffDraft(meta, draftFromMetadata(meta))
        assertEquals(DraftDiff(emptyList(), EntryEdit()), diff)
        assertFalse(draftChanged(diff))
    }

    @Test
    fun reportsEachChangedFieldAndOnlyThose() {
        val meta = current()
        val draft = draftFromMetadata(meta).copy(
            note = "changed",
            tags = listOf("beach"),
            time = EntryTimeEdit("2026-11-02T08:00:00.000Z", 60),
            location = null,
        )
        val diff = diffDraft(meta, draft)
        assertEquals(
            DraftDiff(
                emptyList(),
                EntryEdit(
                    note = "changed",
                    tags = listOf("beach"),
                    time = EntryTimeEdit("2026-11-02T08:00:00.000Z", 60),
                    location = OptionalValue.Present(null),
                ),
            ),
            diff,
        )
        assertTrue(draftChanged(diff))
    }

    @Test
    fun aChangedOffsetAloneIsATimeChange() {
        val meta = current()
        val draft = draftFromMetadata(meta).copy(time = EntryTimeEdit("2026-11-01T08:00:00.000Z", 120))
        assertEquals(EntryEdit(time = EntryTimeEdit("2026-11-01T08:00:00.000Z", 120)), diffDraft(meta, draft).edit)
    }

    @Test
    fun aMovedOrRetaggedLocationIsALocationChange() {
        val meta = current()
        val moved = EntryLocation(48.8585, 2.2945, null, LocationSource.EXIF)
        assertEquals(EntryEdit(location = OptionalValue.Present(moved)), diffDraft(meta, draftFromMetadata(meta).copy(location = moved)).edit)
        val device = EntryLocation(48.8584, 2.2945, null, LocationSource.DEVICE)
        assertEquals(EntryEdit(location = OptionalValue.Present(device)), diffDraft(meta, draftFromMetadata(meta).copy(location = device)).edit)
        val accurate = EntryLocation(48.8584, 2.2945, 12.0, LocationSource.EXIF)
        assertEquals(EntryEdit(location = OptionalValue.Present(accurate)), diffDraft(meta, draftFromMetadata(meta).copy(location = accurate)).edit)
    }

    @Test
    fun reportsAPureReorderAsMediaOrder() {
        val meta = current()
        val draft = draftFromMetadata(meta).copy(mediaIds = listOf(c, a, b))
        assertEquals(DraftDiff(emptyList(), EntryEdit(mediaOrder = listOf(c, a, b))), diffDraft(meta, draft))
    }

    @Test
    fun removesWithoutReorderingWhenTheRemainingOrderIsUnchanged() {
        val meta = current()
        val draft = draftFromMetadata(meta).copy(mediaIds = listOf(a, c))
        val diff = diffDraft(meta, draft)
        assertEquals(DraftDiff(listOf(b), EntryEdit()), diff)
        assertTrue(draftChanged(diff))
    }

    @Test
    fun removesOneAndReordersTheRestWithAnEditValidAfterTheRemoval() {
        val meta = current()
        val draft = draftFromMetadata(meta).copy(mediaIds = listOf(c, a), note = "new note")
        val (removedMediaIds, edit) = diffDraft(meta, draft)
        assertEquals(listOf(b), removedMediaIds)
        assertEquals(EntryEdit(note = "new note", mediaOrder = listOf(c, a)), edit)
        var afterRemovals = meta
        for (id in removedMediaIds) afterRemovals = applyEntryEdit(afterRemovals, EntryEdit(removeMediaId = id), now)
        val result = applyEntryEdit(afterRemovals, edit, now)
        assertEquals(listOf(c, a), result.media.map { it.mediaId })
        assertEquals("new note", result.note)
    }

    @Test
    fun listsSeveralRemovalsInMetadataOrder() {
        val meta = current()
        assertEquals(listOf(a, c), diffDraft(meta, draftFromMetadata(meta).copy(mediaIds = listOf(b))).removedMediaIds)
    }

    @Test
    fun treatsTagsInADifferentOrderAsUnchanged() {
        val meta = current()
        val draft = draftFromMetadata(meta).copy(tags = listOf("family", "beach"))
        assertEquals(DraftDiff(emptyList(), EntryEdit()), diffDraft(meta, draft))
    }

    @Test
    fun carriesCaptionsAndDiffsOnlyTheChangedOnes() {
        val meta = current()
        val draft = draftFromMetadata(meta)
        assertEquals(emptyMap<String, String>(), draft.captions)
        val diff = diffDraft(meta, draft.copy(captions = mapOf(a to "Sunset")))
        assertEquals(EntryEdit(captions = mapOf(a to "Sunset")), diff.edit)
        val applied = applyEntryEdit(meta, diff.edit, now)
        assertEquals(EntryEdit(), diffDraft(applied, draftFromMetadata(applied)).edit)
    }

    @Test
    fun clearsACaptionWithAnEmptyString() {
        val meta = current()
        val withCaption = applyEntryEdit(meta, EntryEdit(captions = mapOf(a to "Old")), now)
        val draft = draftFromMetadata(withCaption)
        assertEquals(mapOf(a to "Old"), draft.captions)
        assertEquals(EntryEdit(captions = mapOf(a to "")), diffDraft(withCaption, draft.copy(captions = draft.captions - a)).edit)
    }

    @Test
    fun ignoresCaptionsOfRemovedItems() {
        val meta = applyEntryEdit(current(), EntryEdit(captions = mapOf(b to "Gone soon")), now)
        val draft = draftFromMetadata(meta).copy(mediaIds = listOf(a, c), captions = emptyMap())
        assertEquals(DraftDiff(listOf(b), EntryEdit()), diffDraft(meta, draft))
    }
}
