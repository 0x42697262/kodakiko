@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.exporting.planEntryExport
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.ImportedMedia
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val PASSPHRASE = "round trip passphrase"

private class SourceEntry(val entryId: String, val media: List<ByteArray>)

@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest : ExportTestBase() {
    private suspend fun seedSource(largePhotoBytes: Int): List<SourceEntry> {
        val beach = patternBytes(40_000, 1)
        val clip = tinyMp4()
        val first = harness.createEntry(listOf(beach, clip), note = "beach day\nwith **markdown**", tags = listOf("sea", "family"), fileNames = listOf("beach.jpg", "clip.mp4"))
        val firstMedia = harness.service.readMetadata(first).media
        harness.service.edit(
            first,
            EntryEdit(
                time = EntryTimeEdit("2026-07-04T18:30:00.000Z", 120),
                location = OptionalValue.Present(EntryLocation(48.8584, 2.2945, 12.0, LocationSource.DEVICE)),
                captions = mapOf(firstMedia[0].mediaId to "Low tide"),
            ),
        )
        harness.service.setFavorite(listOf(first), true)
        harness.clock.advance(60_000)
        val secondPhoto = patternBytes(20_000, 2)
        val second = harness.createEntry(listOf(secondPhoto), note = "second")
        harness.clock.advance(60_000)
        val thirdPhoto = patternBytes(largePhotoBytes, 3)
        val third = harness.createEntry(listOf(thirdPhoto), note = "")
        return listOf(SourceEntry(first, listOf(beach, clip)), SourceEntry(second, listOf(secondPhoto)), SourceEntry(third, listOf(thirdPhoto)))
    }

    private suspend fun EntriesHarness.metadataByTime(): Map<String, Pair<String, EntryMetadataV1>> =
        allEntryIds().associate { entryId -> service.readMetadata(entryId).let { it.takenAtUtc to (entryId to it) } }

    private suspend fun restorer(target: EntriesHarness): RestoreService {
        val existing = target.metadataByTime().values.map { (_, metadata) -> ExistingEntry(metadata.takenAtUtc, metadata.media.size) }
        return DefaultRestoreService(
            RestoreServiceDeps(
                sodium = sodium,
                importMedia = { file: RestoreMediaFile, position: Int ->
                    val temp = File.createTempFile("restore-", ".bin", context.cacheDir)
                    temp.writeBytes(file.bytes)
                    target.pipeline.importOne(MediaInput(MediaOrigin.PICKER, temp, file.fileName, null), ImportSlots(position, TEST_TRAY_LIMIT))
                },
                createEntry = { input: CreateEntryInput, items: List<ImportedMedia> -> target.service.createFromMedia(input, items) },
                setFavorite = { ids: List<String>, favorite: Boolean -> target.service.setFavorite(ids, favorite) },
                setCaptions = { entryId: String, captions: Map<String, String> -> target.service.edit(entryId, EntryEdit(captions = captions)) },
                existingEntries = { existing },
                ioDispatcher = io,
            ),
        )
    }

    private suspend fun assertRestored(source: List<SourceEntry>, target: EntriesHarness) {
        val restored = target.metadataByTime()
        assertEquals(source.size, restored.size)
        for (entry in source) {
            val original = harness.service.readMetadata(entry.entryId)
            val (copyId, copy) = restored.getValue(original.takenAtUtc)
            assertEquals(original.note, copy.note)
            assertEquals(original.tags, copy.tags)
            assertEquals(original.utcOffsetMinutes, copy.utcOffsetMinutes)
            assertEquals(original.favorite == true, copy.favorite == true)
            assertEquals(original.location?.let { EntryLocation(it.latitude, it.longitude, null, it.source) }, copy.location)
            assertEquals(original.media.map { it.kind }, copy.media.map { it.kind })
            assertEquals(original.media.map { it.mimeType }, copy.media.map { it.mimeType })
            assertEquals(original.media.map { it.originalFileName }, copy.media.map { it.originalFileName })
            assertEquals(original.media.map { it.caption }, copy.media.map { it.caption })
            assertEquals(original.media.map { it.originalPlaintextBytes }, copy.media.map { it.originalPlaintextBytes })
            copy.media.forEachIndexed { index, media -> assertArrayEquals(entry.media[index], target.originalPlaintext(copyId, media.mediaId)) }
        }
    }

    @Test
    fun anExportRestoresIntoAFreshAccountWithEveryFactAndTheExactMediaBytes() = backupTest {
        val source = seedSource(largePhotoBytes = 1_500_000)
        val destination = MemoryDestination(ONE_GIB)
        val exported = exporter().export(destination) { }
        assertEquals(ExportSummary(1, 3, 4, exported.bytes, 0, 0, 0), exported)
        val target = EntriesHarness(context, sodium, createAccountFixture(), io)
        val progress = mutableListOf<RestoreProgress>()

        val summary = restorer(target).restore(destination.parts.map { RestoreFile(it.name, it.bytes()) }, null) { step: RestoreProgress -> progress += step }

        assertEquals(RestoreSummary(entries = 3, media = 4, duplicates = 0, failed = emptyList()), summary)
        assertEquals(RestoreProgress(3, 3), progress.last())
        assertRestored(source, target)
        assertEquals("Low tide", target.metadataByTime().getValue("2026-07-04T18:30:00.000Z").second.media[0].caption)
        assertEquals(true, target.metadataByTime().getValue("2026-07-04T18:30:00.000Z").second.favorite)
    }

    @Test
    fun restoringTheSameExportAgainFindsOnlyDuplicates() = backupTest {
        seedSource(largePhotoBytes = 30_000)
        val destination = MemoryDestination(ONE_GIB)
        exporter().export(destination) { }
        val target = EntriesHarness(context, sodium, createAccountFixture(), io)
        val files = destination.parts.map { RestoreFile(it.name, it.bytes()) }
        restorer(target).restore(files, null) { }

        val again = restorer(target).restore(files, null) { }

        assertEquals(RestoreSummary(entries = 0, media = 0, duplicates = 3, failed = emptyList()), again)
        assertEquals(3, target.allEntryIds().size)
    }

    @Test
    fun restoringIntoTheSourceAccountItselfCreatesNothing() = backupTest {
        seedSource(largePhotoBytes = 30_000)
        val destination = MemoryDestination(ONE_GIB)
        exporter().export(destination) { }

        val summary = restorer(harness).restore(destination.parts.map { RestoreFile(it.name, it.bytes()) }, null) { }

        assertEquals(RestoreSummary(0, 0, 3, emptyList()), summary)
        assertEquals(3, harness.allEntryIds().size)
    }

    @Test
    fun anEncryptedMultiPartExportRestoresWithThePassphraseOnly() = backupTest {
        val source = seedSource(largePhotoBytes = 30_000)
        val inner = MemoryDestination(60_000)
        val exported = exporter().export(encryptedDestination(sodium, inner, PASSPHRASE, opsLimit = 2, memLimitBytes = 8_388_608)) { }
        assertTrue(exported.parts > 1)
        assertEquals(exported.parts, inner.parts.size)
        assertTrue(inner.parts.all { it.name.endsWith(".zip.kdkx") && it.closed })
        val files = inner.parts.map { RestoreFile(it.name, it.bytes()) }
        val target = EntriesHarness(context, sodium, createAccountFixture(), io)

        val refused = expectFailure<RestorePassphraseException> { restorer(target).restore(files, "not the passphrase") { } }
        assertEquals(0, target.allEntryIds().size)
        assertEquals(files[0].name, refused.fileName)

        val summary = restorer(target).restore(files.reversed(), PASSPHRASE) { }

        assertEquals(RestoreSummary(entries = 3, media = 4, duplicates = 0, failed = emptyList()), summary)
        assertRestored(source, target)
    }

    @Test
    fun aRestoreFromSomeOfThePartsCreatesWhatItCanAndReportsTheEntriesWithMissingFiles() = backupTest {
        val source = seedSource(largePhotoBytes = 30_000)
        val destination = MemoryDestination(40_000)
        val exported = exporter().export(destination) { }
        assertEquals(4, exported.parts)
        val notes = source.map { planEntryExport(it.entryId, harness.service.readMetadata(it.entryId)).noteFile }
        val onlyFirst = EntriesHarness(context, sodium, createAccountFixture(), io)
        val firstAndThird = EntriesHarness(context, sodium, createAccountFixture(), io)

        val first = restorer(onlyFirst).restore(listOf(RestoreFile(destination.parts[0].name, destination.parts[0].bytes())), null) { }
        val some = restorer(firstAndThird).restore(listOf(0, 2).map { RestoreFile(destination.parts[it].name, destination.parts[it].bytes()) }, null) { }

        assertEquals(RestoreSummary(0, 0, 0, listOf(RestoreFailure(notes[0], RestoreFailureReason.MISSING_MEDIA))), first)
        assertEquals(0, onlyFirst.allEntryIds().size)
        assertEquals(
            RestoreSummary(
                entries = 2,
                media = 2,
                duplicates = 0,
                failed = listOf(RestoreFailure(notes[0], RestoreFailureReason.MISSING_MEDIA), RestoreFailure(notes[2], RestoreFailureReason.MISSING_MEDIA)),
            ),
            some,
        )
        val restored = firstAndThird.metadataByTime()
        assertEquals(listOf("clip.mp4"), restored.getValue("2026-07-04T18:30:00.000Z").second.media.map { it.originalFileName })
        val (secondId, secondCopy) = restored.getValue(harness.service.readMetadata(source[1].entryId).takenAtUtc)
        assertArrayEquals(source[1].media[0], firstAndThird.originalPlaintext(secondId, secondCopy.media[0].mediaId))
    }
}
