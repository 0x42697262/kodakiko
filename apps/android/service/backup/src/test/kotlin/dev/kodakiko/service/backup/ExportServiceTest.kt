@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.exporting.EXPORT_MISSING_FILE
import dev.kodakiko.exporting.EXPORT_README_FILE
import dev.kodakiko.exporting.ExportText
import dev.kodakiko.exporting.planEntryExport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class ExportServiceTest : ExportTestBase() {
    private fun moveOriginalToServer(mediaId: String, uploadState: ObjectUploadState = ObjectUploadState.UPLOADED): ByteArray {
        val row = harness.originalRow(mediaId)
        val stored = harness.fixture.fileStore.readRange(row.id, 0, row.sizeBytes.toInt())
        harness.fixture.fileStore.delete(row.id)
        harness.putObject(row.copy(localState = ObjectLocalState.MISSING, uploadState = uploadState))
        return stored
    }

    private suspend fun firstMediaId(entryId: String): String = harness.service.readMetadata(entryId).media[0].mediaId

    @Test
    fun writesEveryEntryAsAFolderWithEntryMdAndTheOriginalMediaBytes() = backupTest {
        val photo = fakeJpeg(4000, 1)
        val video = tinyMp4()
        val first = harness.createEntry(listOf(photo, video), note = "beach day", tags = listOf("sea"), fileNames = listOf("beach.jpg", "clip.mp4"))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(2000, 2)), note = "second")
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(3000, 3)), note = "third")
        val destination = MemoryDestination(ONE_GIB)
        val progress = CopyOnWriteArrayList<ExportProgress>()

        val summary: ExportSummary = exporter().export(destination) { step: ExportProgress -> progress += step }

        assertEquals(1, summary.parts)
        assertEquals(3, summary.entries)
        assertEquals(4, summary.mediaFiles)
        assertEquals(0, summary.skippedMissing)
        assertEquals(0, summary.skippedUnreadable)
        assertEquals(0, summary.skippedTooLarge)
        assertEquals(1, destination.parts.size)
        assertTrue(destination.parts[0].closed)
        assertFalse(destination.parts[0].aborted)
        assertTrue(Regex("^kodakiko-export-\\d{4}-\\d{2}-\\d{2}-part01\\.zip$").matches(destination.parts[0].name))
        assertEquals("kodakiko-export-2026-11-12-part01.zip", destination.parts[0].name)
        val files = destination.parts[0].files()
        assertTrue(EXPORT_README_FILE in files.keys)
        val notes = files.keys.filter { it.endsWith("/entry.md") }
        assertEquals(3, notes.size)
        val firstFolder = notes.map { it.removeSuffix("entry.md") }.find { utf8(files["${it}entry.md"]).contains("beach day") }
        assertNotNull(firstFolder)
        assertArrayEquals(photo, files["${firstFolder}001.jpg"])
        assertArrayEquals(video, files["${firstFolder}002.mp4"])
        assertTrue(utf8(files["${firstFolder}entry.md"]).contains("tags: [sea]"))
        assertTrue(firstFolder!!.endsWith("_${first.take(8)}/"))
        assertEquals(ExportPhase.DONE, progress.last().phase)
        assertEquals(1, autoLock.paused)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun theArchiveHoldsTheReadmeThenEachEntryInTimeOrderWithItsPlannedNote() = backupTest {
        val first = harness.createEntry(listOf(fakeJpeg(1000, 1)), note = "first")
        harness.clock.advance(3_600_000)
        val second = harness.createEntry(listOf(fakeJpeg(1000, 2), fakeJpeg(1000, 3)), note = "second", tags = listOf("b", "a"))
        val destination = MemoryDestination(ONE_GIB)

        exporter(order = { listOf(second, first) }).export(destination) { }

        val part = destination.parts.single()
        val firstPlan = planEntryExport(first, harness.service.readMetadata(first))
        val secondPlan = planEntryExport(second, harness.service.readMetadata(second))
        assertEquals(
            listOf(
                EXPORT_README_FILE,
                firstPlan.noteFile,
                "${firstPlan.folder}001.jpg",
                secondPlan.noteFile,
                "${secondPlan.folder}001.jpg",
                "${secondPlan.folder}002.jpg",
            ),
            part.names(),
        )
        val files = part.files()
        assertEquals(ExportText.readme("2026-11-12"), utf8(files[EXPORT_README_FILE]))
        assertEquals(firstPlan.noteText, utf8(files[firstPlan.noteFile]))
        assertEquals(secondPlan.noteText, utf8(files[secondPlan.noteFile]))
        assertArrayEquals(fakeJpeg(1000, 3), files["${secondPlan.folder}002.jpg"])
        assertFalse(EXPORT_MISSING_FILE in files.keys)
    }

    @Test
    fun splitsIntoTwoValidZipsWhenThePartLimitIsSmall() = backupTest {
        harness.createEntry(listOf(fakeJpeg(30_000, 1)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        val destination = MemoryDestination(40_000)

        val summary = exporter().export(destination) { }

        assertEquals(2, summary.parts)
        assertEquals(2, destination.parts.size)
        for (part in destination.parts) assertTrue(part.names().any { it.endsWith(".jpg") })
        assertEquals(listOf("kodakiko-export-2026-11-12-part01.zip", "kodakiko-export-2026-11-12-part02.zip"), destination.parts.map { it.name })
        assertTrue(destination.parts.all { it.closed && !it.aborted })
    }

    @Test
    fun theReadmeGoesIntoTheFirstPartAndTheMissingListIntoTheLastPartOnly() = backupTest {
        val first = harness.createEntry(listOf(fakeJpeg(30_000, 1)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 3)))
        val missing = harness.originalRow(firstMediaId(first))
        harness.putObject(missing.copy(localState = ObjectLocalState.UNAVAILABLE))
        val destination = MemoryDestination(40_000)

        val summary = exporter().export(destination) { }

        assertEquals(3, summary.parts)
        assertEquals(1, summary.skippedMissing)
        assertEquals(2, summary.mediaFiles)
        assertEquals(listOf(true, false, false), destination.parts.map { EXPORT_README_FILE in it.names() })
        assertEquals(listOf(false, false, true), destination.parts.map { EXPORT_MISSING_FILE in it.names() })
        val folder = planEntryExport(first, harness.service.readMetadata(first)).folder
        assertEquals("${ExportText.MISSING_HEADER}\n${folder}001.jpg\tmissing\n", utf8(destination.parts[2].files()[EXPORT_MISSING_FILE]))
    }

    @Test
    fun cancelAbortsTheUnfinishedPartAndKeepsTheFinishedOnes() = backupTest {
        harness.createEntry(listOf(fakeJpeg(30_000, 1)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        val export = exporter()
        val destination = MemoryDestination(40_000, onWrite = { part: MemoryPart -> if (part.name.endsWith("part02.zip")) export.cancel() })

        expectFailure<ExportCancelledException> { export.export(destination) { } }

        assertTrue(destination.parts[0].closed)
        assertTrue(destination.parts[1].aborted)
        assertFalse(destination.parts[1].closed)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun cancelStopsInsideALargeOriginalAndAbortsItsPart() = backupTest {
        harness.createEntry(listOf(patternBytes(2_621_440, 5)))
        val export = exporter()
        val destination = MemoryDestination(ONE_GIB, onWrite = { part: MemoryPart -> if (part.chunks.sumOf { it.size } > 1_100_000) export.cancel() })

        expectFailure<ExportCancelledException> { export.export(destination) { } }

        val part = destination.parts.single()
        assertTrue(part.aborted)
        assertFalse(part.closed)
        assertTrue(part.chunks.sumOf { it.size } < 2_621_440)
        assertEquals(1, autoLock.paused)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun aLockCancelsTheExportBeforeItWipesTheKeysAndTheRunEndsAsCancelled() = backupTest {
        harness.createEntry(listOf(fakeJpeg(30_000, 1)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        val export = exporter()
        val destination = MemoryDestination(
            40_000,
            onWrite = { part: MemoryPart ->
                if (part.name.endsWith("part02.zip") && !harness.locked) {
                    export.cancel()
                    harness.entryKeys.clear()
                    harness.locked = true
                }
            },
        )

        expectFailure<ExportCancelledException> { export.export(destination) { } }

        assertTrue(destination.parts[0].closed)
        assertTrue(destination.parts[1].aborted)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun keysWipedWithoutACancelFailTheRunWithTheKeyErrorAndAbortThePart() = backupTest {
        harness.createEntry(listOf(fakeJpeg(30_000, 1)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        val destination = MemoryDestination(
            40_000,
            onWrite = { part: MemoryPart ->
                if (part.name.endsWith("part01.zip") && !harness.locked && part.chunks.size > 1) {
                    harness.entryKeys.clear()
                    harness.locked = true
                }
            },
        )

        val failure = failureOf { exporter().export(destination) { } }

        assertNotNull(failure)
        assertFalse(failure is ExportCancelledException)
        assertTrue(destination.parts.last().aborted)
        assertFalse(destination.parts.last().closed)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun aWriteFailureAbortsThePartRethrowsAndResumesAutoLock() = backupTest {
        harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val destination = MemoryDestination(ONE_GIB, failWritesTo = "part01.zip")

        val failure = expectFailure<IOException> { exporter().export(destination) { } }

        assertEquals("disk full", failure.message)
        assertTrue(destination.parts.single().aborted)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun cancellingTheCoroutineAbortsTheUnfinishedPartAndResumesAutoLock() = backupTest {
        harness.createEntry(listOf(fakeJpeg(30_000, 1)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        val destination = MemoryDestination(40_000)
        val export = exporter()
        lateinit var job: Job

        job = launch {
            export.export(destination) { step: ExportProgress -> if (step.partNumber == 2 && step.phase == ExportPhase.WRITING) job.cancel() }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(destination.parts[0].closed)
        assertTrue(destination.parts[1].aborted)
        assertFalse(destination.parts[1].closed)
        assertEquals(1, autoLock.resumed)
    }

    @Test
    fun aCancelBeforeTheRunIsForgottenAndTheRunCompletes() = backupTest {
        harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val export = exporter()
        export.cancel()

        val summary = export.export(MemoryDestination(ONE_GIB)) { }

        assertEquals(1, summary.mediaFiles)
        val again = export.export(MemoryDestination(ONE_GIB)) { }
        assertEquals(1, again.parts)
        assertEquals(2, autoLock.paused)
        assertEquals(2, autoLock.resumed)
    }

    @Test
    fun autoLockIsPausedBeforeTheEntriesAreReadAndResumedAfterTheRun() = backupTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        val seen = CopyOnWriteArrayList<Pair<Int, Int>>()

        exporter(
            order = {
                seen += autoLock.paused to autoLock.resumed
                listOf(entryId)
            },
        ).export(MemoryDestination(ONE_GIB)) { seen += autoLock.paused to autoLock.resumed }

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it == (1 to 0) })
        assertEquals(1 to 1, autoLock.paused to autoLock.resumed)
    }

    @Test
    fun skipsMediaWhoseOriginalIsMissingAndListsThemInMissingTxt() = backupTest {
        val entryId = harness.createEntry(listOf(fakeJpeg(2000, 4)))
        val mediaId = firstMediaId(entryId)
        val row = harness.originalRow(mediaId)
        harness.putObject(row.copy(localState = ObjectLocalState.UNAVAILABLE))
        val destination = MemoryDestination(ONE_GIB)

        val summary = exporter().export(destination) { }

        assertEquals(1, summary.skippedMissing)
        val files = destination.parts.single().files()
        assertTrue(utf8(files[EXPORT_MISSING_FILE]).contains("001.jpg"))
        assertEquals(emptyList<String>(), fetched.toList())
        assertEquals(0, summary.mediaFiles)
        assertEquals(1, summary.entries)
    }

    @Test
    fun downloadsAMissingOriginalBeforeWritingIt() = backupTest {
        val photo = patternBytes(5000, 6)
        val entryId = harness.createEntry(listOf(photo))
        val mediaId = firstMediaId(entryId)
        val row = harness.originalRow(mediaId)
        val stored = moveOriginalToServer(mediaId)
        fetch = { objectId: String ->
            fetched += objectId
            harness.fixture.fileStore.writeObject(objectId, sequenceOf(stored))
            harness.putObject(row.copy(localState = ObjectLocalState.PRESENT, uploadState = ObjectUploadState.UPLOADED))
        }
        val destination = MemoryDestination(ONE_GIB)

        val summary = exporter().export(destination) { }

        assertEquals(listOf(row.id), fetched.toList())
        assertEquals(1, summary.mediaFiles)
        val folder = planEntryExport(entryId, harness.service.readMetadata(entryId)).folder
        assertArrayEquals(photo, destination.parts.single().files()["${folder}001.jpg"])
    }

    @Test
    fun aFailedOrFruitlessDownloadCountsTheOriginalAsMissing() = backupTest {
        val failing = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        harness.clock.advance(60_000)
        val fruitless = harness.createEntry(listOf(fakeJpeg(1000, 2)))
        val failingRow = harness.originalRow(firstMediaId(failing))
        val fruitlessRow = harness.originalRow(firstMediaId(fruitless))
        moveOriginalToServer(firstMediaId(failing), uploadState = ObjectUploadState.LOCAL)
        moveOriginalToServer(firstMediaId(fruitless))
        fetch = { objectId: String ->
            fetched += objectId
            if (objectId == failingRow.id) throw IOException("offline")
        }
        val destination = MemoryDestination(ONE_GIB)

        val summary = exporter().export(destination) { }

        assertEquals(listOf(failingRow.id, fruitlessRow.id), fetched.toList())
        assertEquals(2, summary.skippedMissing)
        assertEquals(0, summary.mediaFiles)
        val missing = utf8(destination.parts.single().files()[EXPORT_MISSING_FILE]).lines()
        assertEquals(ExportText.MISSING_HEADER, missing[0])
        assertEquals(4, missing.size)
        assertEquals("", missing[3])
    }

    @Test
    fun mediaLargerThanAPartIsSkippedAsTooLargeWhileItsNoteIsExported() = backupTest {
        val big = harness.createEntry(listOf(fakeJpeg(50_000, 1)), note = "big one")
        val destination = MemoryDestination(40_000)

        val summary = exporter().export(destination) { }

        assertEquals(1, summary.skippedTooLarge)
        assertEquals(0, summary.skippedMissing)
        assertEquals(0, summary.mediaFiles)
        assertEquals(1, summary.entries)
        val plan = planEntryExport(big, harness.service.readMetadata(big))
        val files = destination.parts.single().files()
        assertEquals(plan.noteText, utf8(files[plan.noteFile]))
        assertFalse("${plan.folder}001.jpg" in files.keys)
        assertEquals("${ExportText.MISSING_HEADER}\n${plan.folder}001.jpg\ttoo large\n", utf8(files[EXPORT_MISSING_FILE]))
    }

    @Test
    fun anEntryWhoseMetadataCannotBeReadIsSkippedAsUnreadable() = backupTest {
        val good = harness.createEntry(listOf(fakeJpeg(1000, 1)))
        harness.clock.advance(60_000)
        val broken = harness.createEntry(listOf(fakeJpeg(1000, 2)))
        val read: suspend (String) -> EntryMetadataV1 = { entryId: String ->
            if (entryId == broken) error("cannot open") else harness.service.readMetadata(entryId)
        }
        val destination = MemoryDestination(ONE_GIB)

        val summary = exporter(order = { listOf(good, broken) }, readMetadata = read).export(destination) { }

        assertEquals(1, summary.entries)
        assertEquals(1, summary.skippedUnreadable)
        assertEquals(1, destination.parts.single().names().count { it.endsWith("/entry.md") })
        assertFalse(EXPORT_MISSING_FILE in destination.parts.single().names())
    }

    @Test
    fun reportsPlanningWritingDownloadingAndDoneWithByteAndPartCounts() = backupTest {
        val photo = fakeJpeg(1500, 1)
        val entryId = harness.createEntry(listOf(photo))
        val noteBytes = planEntryExport(entryId, harness.service.readMetadata(entryId)).noteText.encodeToByteArray().size.toLong()
        val progress = CopyOnWriteArrayList<ExportProgress>()

        val summary = exporter().export(MemoryDestination(ONE_GIB)) { step: ExportProgress -> progress += step }

        assertEquals(
            listOf(
                ExportProgress(ExportPhase.PLANNING, 0, 0, 0, 0, 0),
                ExportProgress(ExportPhase.WRITING, 0, 1, 0, 1, 1),
                ExportProgress(ExportPhase.WRITING, 0, 1, noteBytes, 1, 1),
                ExportProgress(ExportPhase.DOWNLOADING, 0, 1, noteBytes, 1, 1),
                ExportProgress(ExportPhase.DONE, 1, 1, noteBytes + photo.size, 1, 1),
            ),
            progress.toList(),
        )
        assertEquals(noteBytes + photo.size, summary.bytes)
    }

    @Test
    fun theOriginalCacheCapIsEnforcedAfterEveryWrittenMediaFile() = backupTest {
        harness.createEntry(listOf(fakeJpeg(1000, 1), fakeJpeg(1000, 2)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(1000, 3)))
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(1000, 4)))

        exporter().export(MemoryDestination(ONE_GIB)) { }

        assertEquals(4, capChecks.get())
    }

    @Test
    fun anEncryptedExportWritesKdkxPartsThatDecryptToTheSameZip() = backupTest {
        val photo = patternBytes(30_000, 3)
        val entryId = harness.createEntry(listOf(photo), note = "secret")
        harness.clock.advance(60_000)
        harness.createEntry(listOf(fakeJpeg(30_000, 2)))
        val inner = MemoryDestination(40_000)
        val destination = encryptedDestination(sodium, inner, "export passphrase", opsLimit = 2, memLimitBytes = 8_388_608)

        val summary = exporter().export(destination) { }

        assertEquals(2, summary.parts)
        assertEquals(
            listOf("kodakiko-export-2026-11-12-part01.zip.kdkx", "kodakiko-export-2026-11-12-part02.zip.kdkx"),
            inner.parts.map { it.name },
        )
        assertTrue(inner.parts.all { it.closed && !it.aborted })
        val zips = inner.parts.map { part -> readZipOfKdkx(sodium, part.bytes(), "export passphrase") }
        val plan = planEntryExport(entryId, harness.service.readMetadata(entryId))
        assertEquals(plan.noteText, utf8(zips[0][plan.noteFile]))
        assertArrayEquals(photo, zips[0]["${plan.folder}001.jpg"])
        assertTrue(EXPORT_README_FILE in zips[0].keys)
        assertTrue(zips[1].keys.any { it.endsWith(".jpg") })
    }
}
