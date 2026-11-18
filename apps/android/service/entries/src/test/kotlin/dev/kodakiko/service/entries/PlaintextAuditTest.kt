package dev.kodakiko.service.entries

import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.TempPurpose
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

const val AUDIT_ACCOUNT_HASH = "0123456789abcdef"
const val AUDIT_NEEDLE_BYTES = 64
const val AUDIT_THUMBNAIL_BYTES = 2048
val AUDIT_THUMBNAIL = ByteArray(AUDIT_THUMBNAIL_BYTES) { ((it * 31 + 17) % 253).toByte() }

@RunWith(RobolectricTestRunner::class)
class PlaintextAuditTest : EntriesTestBase() {
    private class Audit(val pipeline: ImportPipeline, val fileStore: FileStore, val platform: FakePlatformMedia)

    private fun audit(
        probe: ProbeResult = ProbeResult(2000, 1500, null, true, 0),
        config: PublicConfigDto? = testConfig(),
        encryptThumbnails: Boolean = true,
        wrapFiles: (EntryFiles) -> EntryFiles = { it },
    ): Audit {
        val fileStore = openFileStore()
        val platform = FakePlatformMedia(probe, ThumbnailResult(AUDIT_THUMBNAIL, 512, 384))
        val pipeline = DefaultImportPipeline(
            ImportPipelineDeps(
                sodium = sodium,
                files = wrapFiles(FileStoreEntryFiles { fileStore }),
                toolkit = FileMediaToolkit(platform),
                clock = TestClock(),
                config = { config },
                deviceSettings = FixedDeviceSettings(settingsWith(encryptThumbnails)),
                randomId = randomIdOf(sodium),
                ioDispatcher = Dispatchers.IO,
            ),
        )
        return Audit(pipeline, fileStore, platform)
    }

    private fun staged(bytes: ByteArray, extension: String): File {
        val temp = TempFiles(context, sodium) { AUDIT_ACCOUNT_HASH }.create(TempPurpose.IMPORT, extension)
        temp.write(bytes)
        temp.close()
        return temp.file
    }

    private fun input(file: File, origin: MediaOrigin = MediaOrigin.PICKER): MediaInput =
        MediaInput(origin = origin, file = file, fileName = if (origin == MediaOrigin.CAMERA) null else file.name, pickerExif = null)

    private fun needleOf(bytes: ByteArray): ByteArray {
        val start = maxOf(0, bytes.size / 2 - AUDIT_NEEDLE_BYTES / 2)
        return bytes.copyOfRange(start, minOf(bytes.size, start + AUDIT_NEEDLE_BYTES))
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        val first = needle[0]
        for (start in 0..haystack.size - needle.size) {
            if (haystack[start] != first) continue
            var index = 1
            while (index < needle.size && haystack[start + index] == needle[index]) index += 1
            if (index == needle.size) return true
        }
        return false
    }

    private fun filesHolding(plaintext: ByteArray): List<String> {
        val needle = needleOf(plaintext)
        return listOf(context.filesDir, context.cacheDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { containsBytes(it.readBytes(), needle) }
            .map { it.path }
    }

    private fun assertNoPlaintextLeft(vararg plaintexts: ByteArray) {
        for (plaintext in plaintexts) assertEquals(emptyList<String>(), filesHolding(plaintext))
    }

    @Test
    fun anImportedPhotoLeavesNoPlaintextCopyBehind() = runTest {
        val bytes = fixtureMedia("photo-gps.jpg").readBytes()
        val file = staged(bytes, "jpg")
        val audit = audit()
        assertEquals(listOf(file.path), filesHolding(bytes))

        val media = expectImported(audit.pipeline.importOne(input(file), IMPORT_SLOTS))

        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes, AUDIT_THUMBNAIL)
        assertArrayEquals(bytes, decryptStoredObject(sodium, audit.fileStore, media.originalObjectId, media.mediaKey, ObjectVariant.ORIGINAL, bytes.size.toLong()))
        assertArrayEquals(
            AUDIT_THUMBNAIL,
            decryptStoredObject(sodium, audit.fileStore, media.thumbnailObjectId, media.mediaKey, ObjectVariant.THUMBNAIL, AUDIT_THUMBNAIL.size.toLong()),
        )
    }

    @Test
    fun anImportedVideoLeavesNoPlaintextCopyBehind() = runTest {
        val bytes = fixtureMedia("video-gps.mov").readBytes()
        val file = staged(bytes, "mov")
        val audit = audit(probe = ProbeResult(640, 480, 3000, true, 0))

        expectImported(audit.pipeline.importOne(input(file), IMPORT_SLOTS))

        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes, AUDIT_THUMBNAIL)
    }

    @Test
    fun anImportedVoiceNoteLeavesNoPlaintextCopyBehind() = runTest {
        val bytes = fixtureMedia("voice-opus.webm").readBytes()
        val file = staged(bytes, "webm")
        val audit = audit()

        val media = expectImported(audit.pipeline.importVoiceNote(input(file, MediaOrigin.CAMERA), VoiceNoteInfo(durationMs = 2000, mimeType = "audio/webm"), IMPORT_SLOTS))

        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes, media.thumbnailBytes)
        assertEquals(0, audit.platform.probed.size)
        assertEquals(0, audit.platform.thumbnails.size)
    }

    @Test
    fun anUnsupportedFileIsRefusedAndDeleted() = runTest {
        val bytes = ByteArray(4096) { ((it * 13 + 5) % 251).toByte() }
        val file = staged(bytes, "bin")
        val audit = audit()

        val outcome = audit.pipeline.importOne(input(file), IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.UNSUPPORTED, file.name), outcome)
        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes)
        assertEquals(0, audit.fileStore.listSizes().size)
    }

    @Test
    fun anUndecodableFileIsRefusedAndDeleted() = runTest {
        val bytes = fixtureMedia("photo-portrait.jpg").readBytes()
        val file = staged(bytes, "jpg")
        val audit = audit(probe = ProbeResult.UNDECODABLE)

        val outcome = audit.pipeline.importOne(input(file), IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.UNDECODABLE, file.name), outcome)
        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes)
        assertEquals(0, audit.fileStore.listSizes().size)
    }

    @Test
    fun aTooLargeFileIsRefusedAndDeleted() = runTest {
        val bytes = fixtureMedia("photo-portrait.jpg").readBytes()
        val file = staged(bytes, "jpg")
        val audit = audit(config = testConfig(maxUploadBytes = 1000))

        val outcome = audit.pipeline.importOne(input(file), IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.TOO_LARGE, file.name), outcome)
        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes)
        assertEquals(0, audit.platform.thumbnails.size)
    }

    @Test
    fun aFullTrayRefusesAndStillDeletesTheInput() = runTest {
        val bytes = fixtureMedia("photo-portrait.jpg").readBytes()
        val file = staged(bytes, "jpg")
        val audit = audit()

        val outcome = audit.pipeline.importOne(input(file), ImportSlots(used = 20, limit = 20))

        assertEquals(ImportOutcome.Refused(ImportRefusal.TRAY_FULL, file.name), outcome)
        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes)
    }

    @Test
    fun aFailedWriteLeavesNeitherPlaintextNorCiphertextBehind() = runTest {
        val bytes = fixtureMedia("photo-gps.jpg").readBytes()
        val file = staged(bytes, "jpg")
        val audit = audit(wrapFiles = { FailingObjectWrites(it, 2) })

        val outcome = audit.pipeline.importOne(input(file), IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.READ_FAILED, file.name), outcome)
        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes, AUDIT_THUMBNAIL)
        assertEquals(0, audit.fileStore.listSizes().size)
    }

    @Test
    fun thePlainThumbnailIsTheOnlyPlaintextWhenThumbnailsAreNotEncrypted() = runTest {
        val bytes = fixtureMedia("photo-gps.jpg").readBytes()
        val file = staged(bytes, "jpg")
        val audit = audit(encryptThumbnails = false)

        val media = expectImported(audit.pipeline.importOne(input(file), IMPORT_SLOTS))

        assertFalse(file.exists())
        assertNoPlaintextLeft(bytes)
        val plain = audit.fileStore.plainThumbnailFile(media.mediaId)
        assertNotNull(plain)
        assertEquals(listOf(plain?.path), filesHolding(AUDIT_THUMBNAIL))
        assertTrue(audit.platform.thumbnails.isNotEmpty())
    }
}
