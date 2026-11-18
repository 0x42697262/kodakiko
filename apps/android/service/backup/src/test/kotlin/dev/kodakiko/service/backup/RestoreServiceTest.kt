@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.exporting.ExportEntryPlan
import dev.kodakiko.exporting.KDKX_SALT_BYTES
import dev.kodakiko.exporting.KdkxParams
import dev.kodakiko.exporting.ZipFormatException
import dev.kodakiko.exporting.ZipTimeZone
import dev.kodakiko.exporting.ZipWriter
import dev.kodakiko.exporting.createKdkxWriter
import dev.kodakiko.exporting.deriveKdkxKey
import dev.kodakiko.exporting.planEntryExport
import dev.kodakiko.js.JsDate
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportedMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

private const val FAST_OPS = 2L
private const val FAST_MEM = 8_388_608L
private const val TAKEN = "2026-03-01T10:00:00.000Z"

private val MEDIA = MediaDescriptorV1(
    mediaId = "mmmmmmmmmmmmmmmmmmmm01",
    kind = MediaKind.PHOTO,
    mimeType = "image/jpeg",
    width = 10,
    height = 10,
    durationMs = null,
    originalPlaintextBytes = 3,
    thumbnail = ThumbnailDescriptor(10, 10, 10),
    capturedAtUtc = null,
    originalFileName = "beach.jpg",
)

private val BASE = EntryMetadataV1(
    takenAtUtc = TAKEN,
    utcOffsetMinutes = 60,
    timeZone = null,
    note = "hello",
    tags = listOf("trip"),
    location = EntryLocation(1.5, 2.5, null, LocationSource.EXIF),
    media = listOf(MEDIA),
    createdAtUtc = TAKEN,
    editedAtUtc = TAKEN,
)

private val MEDIA_BYTES = byteArrayOf(1, 2, 3)

private fun noteFor(id: String, metadata: EntryMetadataV1 = BASE): ExportEntryPlan = planEntryExport(id, metadata)

fun zipOf(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = ZipWriter({ bytes: ByteArray -> out.write(bytes) }, ZipTimeZone.UTC)
    for ((name, bytes) in files) writer.addFile(name, compress = true, mtime = JsDate.parse(TAKEN)).push(bytes, true)
    writer.finish()
    return out.toByteArray()
}

private fun exportZip(plans: List<ExportEntryPlan>, skipMedia: Set<String> = emptySet()): ByteArray {
    val files = LinkedHashMap<String, ByteArray>()
    files["README.txt"] = "readme".encodeToByteArray()
    for (plan in plans) {
        files[plan.noteFile] = plan.noteText.encodeToByteArray()
        for (media in plan.media) if ("${plan.folder}${media.fileName}" !in skipMedia) files["${plan.folder}${media.fileName}"] = MEDIA_BYTES
    }
    return zipOf(files)
}

private fun encrypt(sodium: Sodium, bytes: ByteArray, passphrase: String): ByteArray {
    val params = KdkxParams(sodium.randomBytes(KDKX_SALT_BYTES), FAST_OPS, FAST_MEM)
    val writer = createKdkxWriter(sodium, deriveKdkxKey(sodium, passphrase, params), params)
    return joinBytes(writer.header, *writer.push(bytes).toTypedArray(), writer.finish())
}

fun fakeImportedMedia(mediaId: String, fileName: String): ImportedMedia =
    ImportedMedia(
        mediaId = mediaId,
        mediaKey = ByteArray(32),
        originalObjectId = "o-$mediaId",
        thumbnailObjectId = "t-$mediaId",
        originalCiphertextBytes = 0,
        thumbnailCiphertextBytes = 0,
        descriptor = MEDIA.copy(mediaId = mediaId, originalFileName = fileName),
        time = ResolvedTime(TAKEN, 0, TimeSource.NOW),
        location = null,
        thumbnailBytes = ByteArray(0),
    )

private data class Created(val input: CreateEntryInput, val files: List<String?>)

private class Fakes(existing: List<ExistingEntry>, refuse: Set<String>, io: CoroutineDispatcher, sodium: Sodium) {
    val created = mutableListOf<Created>()
    val favorites = mutableListOf<String>()
    val captions = mutableListOf<Pair<String, Map<String, String>>>()
    val imported = mutableListOf<Pair<String, Int>>()
    val importedBytes = mutableListOf<ByteArray>()
    private var next = 0

    val service: RestoreService = DefaultRestoreService(
        RestoreServiceDeps(
            sodium = sodium,
            importMedia = { file: RestoreMediaFile, position: Int ->
                imported += file.fileName to position
                importedBytes += file.bytes
                if (file.fileName in refuse) {
                    ImportOutcome.Refused(ImportRefusal.UNSUPPORTED, file.fileName)
                } else {
                    next += 1
                    ImportOutcome.Imported(fakeImportedMedia("m$next", file.fileName))
                }
            },
            createEntry = { input: CreateEntryInput, items: List<ImportedMedia> ->
                created += Created(input, items.map { it.descriptor.originalFileName })
                "e${created.size}"
            },
            setFavorite = { ids: List<String>, favorite: Boolean -> if (favorite) favorites += ids },
            setCaptions = { entryId: String, values: Map<String, String> -> captions += entryId to values },
            existingEntries = { existing },
            ioDispatcher = io,
        ),
    )
}

@RunWith(RobolectricTestRunner::class)
class RestoreServiceTest {
    private val sodium: Sodium = createSodium()

    private fun TestScope.fakes(existing: List<ExistingEntry> = emptyList(), refuse: Set<String> = emptySet()): Fakes =
        Fakes(existing, refuse, UnconfinedTestDispatcher(testScheduler), sodium)

    @Test
    fun createsAnEntryPerEntryMdWithItsTimeOffsetTagsPlaceNoteAndMediaInOrder() = runTest {
        val fakes = fakes()

        val summary: RestoreSummary = fakes.service.restore(listOf(RestoreFile("kodakiko-export-01.zip", exportZip(listOf(noteFor("A", BASE.copy(favorite = true)))))), null) { }

        assertEquals(RestoreSummary(entries = 1, media = 1, duplicates = 0, failed = emptyList()), summary)
        assertEquals(
            Created(
                CreateEntryInput(
                    note = "hello",
                    tags = listOf("trip"),
                    time = EntryTimeEdit(TAKEN, 60),
                    location = OptionalValue.Present(EntryLocation(1.5, 2.5, null, LocationSource.EXIF)),
                ),
                listOf("beach.jpg"),
            ),
            fakes.created[0],
        )
        assertEquals(listOf("e1"), fakes.favorites)
        assertEquals(listOf("beach.jpg" to 0), fakes.imported)
        assertArrayEquals(MEDIA_BYTES, fakes.importedBytes.single())
    }

    @Test
    fun opensEncryptedPartsWithThePassphraseAndRefusesAWrongOrMissingOne() = runTest {
        val encrypted = encrypt(sodium, exportZip(listOf(noteFor("A"))), "secret words here")
        val fakes = fakes()

        val missing = expectFailure<RestorePassphraseException> { fakes.service.restore(listOf(RestoreFile("part.zip.kdkx", encrypted)), null) { } }
        val wrong = expectFailure<RestorePassphraseException> { fakes.service.restore(listOf(RestoreFile("part.zip.kdkx", encrypted)), "wrong words") { } }
        val summary = fakes.service.restore(listOf(RestoreFile("part.zip.kdkx", encrypted)), "secret words here") { }

        assertEquals(1, summary.entries)
        assertEquals(1, fakes.created.size)
        assertEquals("part.zip.kdkx", missing.fileName)
        assertEquals("part.zip.kdkx", wrong.fileName)
        assertEquals("part.zip.kdkx", wrong.message)
    }

    @Test
    fun skipsEntriesThatAlreadyExistWithTheSameTimeAndNumberOfItemsAndReportsMissingFiles() = runTest {
        val fakes = fakes(existing = listOf(ExistingEntry(TAKEN, 1)))
        val other = noteFor("B", BASE.copy(takenAtUtc = "2026-04-01T10:00:00.000Z"))
        val zip = zipOf(mapOf(other.noteFile to other.noteText.encodeToByteArray()))

        val summary = fakes.service.restore(listOf(RestoreFile("a.zip", exportZip(listOf(noteFor("A")))), RestoreFile("b.zip", zip)), null) { }

        assertEquals(1, summary.duplicates)
        assertEquals(0, summary.entries)
        assertEquals(listOf(RestoreFailure(other.noteFile, RestoreFailureReason.MISSING_MEDIA)), summary.failed)
        assertEquals(0, fakes.created.size)
    }

    @Test
    fun restoresCaptionsOnTheImportedMedia() = runTest {
        val fakes = fakes()
        val captioned = noteFor("C", BASE.copy(media = listOf(MEDIA.copy(caption = "Low tide"))))

        fakes.service.restore(listOf(RestoreFile("c.zip", exportZip(listOf(captioned)))), null) { }

        assertEquals(listOf("e1" to mapOf("m1" to "Low tide")), fakes.captions)
    }

    @Test
    fun aRefusedItemIsLeftOutAndReportedWhileTheRestOfTheEntryIsCreated() = runTest {
        val fakes = fakes(refuse = setOf("bad.jpg"))
        val two = noteFor("D", BASE.copy(media = listOf(MEDIA.copy(mediaId = "mmmmmmmmmmmmmmmmmmmm02", originalFileName = "bad.jpg"), MEDIA)))
        val alone = noteFor("E", BASE.copy(takenAtUtc = "2026-05-01T10:00:00.000Z", media = listOf(MEDIA.copy(originalFileName = "bad.jpg"))))

        val summary = fakes.service.restore(listOf(RestoreFile("d.zip", exportZip(listOf(two, alone)))), null) { }

        assertEquals(1, summary.entries)
        assertEquals(1, summary.media)
        assertEquals(
            listOf(RestoreFailure(two.noteFile, RestoreFailureReason.REFUSED), RestoreFailure(alone.noteFile, RestoreFailureReason.REFUSED)),
            summary.failed,
        )
        assertEquals(listOf("beach.jpg"), fakes.created.single().files)
        assertEquals(listOf("bad.jpg" to 0, "beach.jpg" to 1, "bad.jpg" to 0), fakes.imported)
    }

    @Test
    fun anEntryMissingOneOfItsFilesIsCreatedWithTheOthersAndReported() = runTest {
        val fakes = fakes()
        val two = noteFor("F", BASE.copy(media = listOf(MEDIA, MEDIA.copy(mediaId = "mmmmmmmmmmmmmmmmmmmm02", originalFileName = "gone.jpg"))))

        val summary = fakes.service.restore(listOf(RestoreFile("f.zip", exportZip(listOf(two), skipMedia = setOf("${two.folder}002.jpg")))), null) { }

        assertEquals(RestoreSummary(1, 1, 0, listOf(RestoreFailure(two.noteFile, RestoreFailureReason.MISSING_MEDIA))), summary)
        assertEquals(listOf("beach.jpg"), fakes.created.single().files)
    }

    @Test
    fun anEntryMdThatCannotBeParsedIsReportedAsUnreadable() = runTest {
        val fakes = fakes()
        val good = noteFor("G")
        val zip = zipOf(
            linkedMapOf(
                "2020/01/2020-01-01_0000_broken/entry.md" to "not an entry".encodeToByteArray(),
                good.noteFile to good.noteText.encodeToByteArray(),
                "${good.folder}001.jpg" to MEDIA_BYTES,
            ),
        )

        val summary = fakes.service.restore(listOf(RestoreFile("g.zip", zip)), null) { }

        assertEquals(RestoreSummary(1, 1, 0, listOf(RestoreFailure("2020/01/2020-01-01_0000_broken/entry.md", RestoreFailureReason.UNREADABLE))), summary)
    }

    @Test
    fun reportsProgressPerEntryAndOnceMoreAtTheEnd() = runTest {
        val fakes = fakes()
        val progress = mutableListOf<RestoreProgress>()
        val later = noteFor("H", BASE.copy(takenAtUtc = "2026-04-01T10:00:00.000Z"))

        fakes.service.restore(listOf(RestoreFile("h.zip", exportZip(listOf(noteFor("A"), later)))), null) { step: RestoreProgress -> progress += step }

        assertEquals(listOf(RestoreProgress(0, 2), RestoreProgress(1, 2), RestoreProgress(2, 2)), progress)
    }

    @Test
    fun entriesAreRestoredInPathOrderAcrossPartsAndALaterPartWinsForTheSamePath() = runTest {
        val fakes = fakes()
        val april = noteFor("I", BASE.copy(takenAtUtc = "2026-04-01T10:00:00.000Z", note = "april"))
        val march = noteFor("J", BASE.copy(note = "march"))
        val marchLater = noteFor("J", BASE.copy(note = "march again"))

        fakes.service.restore(
            listOf(
                RestoreFile("one.zip", exportZip(listOf(april, march))),
                RestoreFile("two.zip", exportZip(listOf(marchLater))),
            ),
            null,
        ) { }

        assertEquals(listOf("march again", "april"), fakes.created.map { it.input.note })
    }

    @Test
    fun aSecondEntryWithTheSameTimeAndItemCountInOneRestoreCountsAsADuplicate() = runTest {
        val fakes = fakes()

        val summary = fakes.service.restore(listOf(RestoreFile("k.zip", exportZip(listOf(noteFor("K"), noteFor("L"))))), null) { }

        assertEquals(RestoreSummary(1, 1, 1, emptyList()), summary)
    }

    @Test
    fun theDuplicateKeyUsesTheItemCountSoADifferentCountIsRestored() = runTest {
        val fakes = fakes(existing = listOf(ExistingEntry(TAKEN, 2)))

        val summary = fakes.service.restore(listOf(RestoreFile("m.zip", exportZip(listOf(noteFor("M"))))), null) { }

        assertEquals(RestoreSummary(1, 1, 0, emptyList()), summary)
    }

    @Test
    fun noFavoriteAndNoCaptionsLeaveThoseCallsOut() = runTest {
        val fakes = fakes()
        fakes.service.restore(listOf(RestoreFile("n.zip", exportZip(listOf(noteFor("N"))))), null) { }
        assertEquals(emptyList<String>(), fakes.favorites)
        assertEquals(emptyList<Pair<String, Map<String, String>>>(), fakes.captions)
    }

    @Test
    fun aDeviceLocationStaysADeviceLocationAndNoLocationStaysNone() = runTest {
        val fakes = fakes()
        val device = noteFor("O", BASE.copy(location = EntryLocation(-33.5, 151.25, 9.0, LocationSource.DEVICE)))
        val none = noteFor("P", BASE.copy(takenAtUtc = "2026-04-01T10:00:00.000Z", location = null))

        fakes.service.restore(listOf(RestoreFile("o.zip", exportZip(listOf(device, none)))), null) { }

        assertEquals(
            listOf<OptionalValue<EntryLocation?>>(
                OptionalValue.Present(EntryLocation(-33.5, 151.25, null, LocationSource.DEVICE)),
                OptionalValue.Present(null),
            ),
            fakes.created.map { it.input.location },
        )
    }

    @Test
    fun anItemWithoutAnOriginalNameIsImportedUnderItsExportFileName() = runTest {
        val fakes = fakes()
        val unnamed = noteFor("Q", BASE.copy(media = listOf(MEDIA.copy(originalFileName = null))))

        fakes.service.restore(listOf(RestoreFile("q.zip", exportZip(listOf(unnamed)))), null) { }

        assertEquals(listOf("001.jpg" to 0), fakes.imported)
    }

    @Test
    fun anEmptyPassphraseCountsAsMissingAndAPlainZipIgnoresAPassphrase() = runTest {
        val encrypted = encrypt(sodium, exportZip(listOf(noteFor("R"))), "secret words here")
        val fakes = fakes()

        val empty = expectFailure<RestorePassphraseException> { fakes.service.restore(listOf(RestoreFile("r.zip.kdkx", encrypted)), "") { } }
        val plain = fakes.service.restore(listOf(RestoreFile("r.zip", exportZip(listOf(noteFor("R"))))), "unused passphrase") { }

        assertEquals("r.zip.kdkx", empty.fileName)
        assertEquals(1, plain.entries)
    }

    @Test
    fun aFileThatIsNeitherAZipNorAnEncryptedExportFailsTheRestore() = runTest {
        val fakes = fakes()
        expectFailure<ZipFormatException> { fakes.service.restore(listOf(RestoreFile("notes.txt", "just text".encodeToByteArray())), null) { } }
        assertEquals(0, fakes.created.size)
    }

    @Test
    fun aZipWithoutEntriesRestoresNothing() = runTest {
        val fakes = fakes()
        val progress = mutableListOf<RestoreProgress>()

        val summary = fakes.service.restore(listOf(RestoreFile("empty.zip", zipOf(mapOf("README.txt" to "readme".encodeToByteArray())))), null) { step: RestoreProgress -> progress += step }

        assertEquals(RestoreSummary(0, 0, 0, emptyList()), summary)
        assertEquals(listOf(RestoreProgress(0, 0)), progress)
    }
}
