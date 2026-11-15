package dev.kodakiko.service.entries

import dev.kodakiko.domain.EntryEdit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplaceMediaTest : EntriesTestBase() {
    @Test
    fun putsTheNewItemWhereTheOldOneWasKeepsItsCaptionAndDropsTheOldOne() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2), fakeJpeg(PHOTO_BYTES, 3)))
        val (first, old, third) = harness.metadataOf(entryId).media.map { it.mediaId }
        harness.service.edit(entryId, EntryEdit(captions = mapOf(old to "Sunset")))
        val calls = mutableListOf<Pair<MediaInput, ImportSlots>>()
        val cropped = harness.pickerInput(fakeJpeg(PHOTO_BYTES, 4), "cropped.jpg")
        var newId = ""

        val result = replaceMedia(
            entries = harness.service,
            importOne = { input, slots ->
                calls += input to slots
                harness.pipeline.importOne(input, slots).also { newId = (it as ImportOutcome.Imported).media.mediaId }
            },
            limit = TEST_TRAY_LIMIT,
            entryId = entryId,
            oldMediaId = old,
            input = cropped,
        )

        assertEquals(ReplaceResult.REPLACED, result)
        val media = harness.metadataOf(entryId).media
        assertEquals(listOf(first, newId, third), media.map { it.mediaId })
        assertEquals(listOf(null, "Sunset", null), media.map { it.caption })
        assertEquals(1, calls.size)
        assertSame(cropped, calls[0].first)
        assertEquals(ImportSlots(used = 2, limit = TEST_TRAY_LIMIT), calls[0].second)
        assertEquals(3, harness.rowsOf(entryId).media.size)
        assertNull(harness.rowsOf(entryId).media.firstOrNull { it.id == old })
    }

    @Test
    fun changesNothingWhenTheImportIsRefused() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1), fakeJpeg(PHOTO_BYTES, 2)))
        val ids = harness.metadataOf(entryId).media.map { it.mediaId }
        val outboxBefore = harness.outboxKinds()

        val result = replaceMedia(
            entries = harness.service,
            importOne = { input, _ -> ImportOutcome.Refused(ImportRefusal.UNDECODABLE, input.fileName) },
            limit = TEST_TRAY_LIMIT,
            entryId = entryId,
            oldMediaId = ids[1],
            input = harness.pickerInput(fakeJpeg(PHOTO_BYTES, 3)),
        )

        assertEquals(ReplaceResult.REFUSED, result)
        assertEquals(ids, harness.metadataOf(entryId).media.map { it.mediaId })
        assertEquals(outboxBefore, harness.outboxKinds())
    }

    @Test
    fun refusesWithoutImportingWhenTheOldItemIsNotInTheEntry() = runTest {
        val harness = harness()
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES, 1)))
        var imports = 0

        val result = replaceMedia(
            entries = harness.service,
            importOne = { input, _ ->
                imports += 1
                ImportOutcome.Refused(ImportRefusal.UNDECODABLE, input.fileName)
            },
            limit = TEST_TRAY_LIMIT,
            entryId = entryId,
            oldMediaId = UNKNOWN_MEDIA_ID,
            input = harness.pickerInput(fakeJpeg(PHOTO_BYTES, 3)),
        )

        assertEquals(ReplaceResult.REFUSED, result)
        assertEquals(0, imports)
    }
}
