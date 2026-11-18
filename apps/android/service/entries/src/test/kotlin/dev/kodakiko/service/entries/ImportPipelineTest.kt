package dev.kodakiko.service.entries

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.streamLayout
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.media.MP4_EPOCH_OFFSET_SECONDS
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

const val IMPORT_MEDIA_ID = "id00000000000000000001"
const val IMPORT_ORIGINAL_OBJECT_ID = "id00000000000000000002"
const val IMPORT_THUMBNAIL_OBJECT_ID = "id00000000000000000003"
const val EXIF_DATE = "2026:05:01 10:00:00"
const val EXIF_OFFSET = "+02:00"
const val EXIF_CAPTURED_UTC = "2026-05-01T08:00:00.000Z"
const val FILE_MODIFIED_UTC = "2026-03-01T12:00:00.000Z"
const val IMPORT_THUMBNAIL_WIDTH = 512
const val IMPORT_THUMBNAIL_HEIGHT = 384
const val MEBIBYTE = 1_048_576
const val TWO_AND_A_HALF_MEBIBYTES = MEBIBYTE * 5 / 2
const val YIELD_WAIT_SECONDS = 2L
const val VOICE_THUMBNAIL_SHA256 = "aacd46294d73fcbf13c0895d92b8d94daa0b2a25cafc4d0db96d06ad1d5a760f"
val IMPORT_THUMBNAIL_BYTES = ByteArray(100) { ((it * 3) % 251).toByte() }
val PHOTO_PROBE = ProbeResult(4000, 3000, null, true, 0)
val VIDEO_PROBE = ProbeResult(1280, 720, 5000, true, 0)
val IMPORT_SLOTS = ImportSlots(used = 0, limit = 20)
val WEBM_HEADER = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0x9f.toByte(), 0x42, 0x86.toByte(), 0x81.toByte())

fun parisTiff(): ByteArray = buildTiff(dateTimeOriginal = EXIF_DATE, offsetTimeOriginal = EXIF_OFFSET, gps = PARIS)

fun jpegBytes(tiff: ByteArray? = null, scanBytes: Int = 256): ByteArray =
    buildJpeg(segments = listOfNotNull(jfifApp0(), tiff?.let(::exifApp1)), scanBytes = scanBytes)

fun heicBytes(): ByteArray = joinBytes(ftypBox("heic", listOf("mif1", "heic")), ByteArray(200))

fun mp4Bytes(rotation: TestRotation = TestRotation.DEGREES_0): ByteArray =
    joinBytes(
        ftypBox("isom", listOf("isom")),
        isoBox("mdat", ByteArray(100)),
        moovBox(
            mvhdBox(mp4Seconds(EXIF_CAPTURED_UTC, MP4_EPOCH_OFFSET_SECONDS)),
            trakBox("vide", tkhdBox(rotationMatrix(rotation))),
            udtaBox(xyzBox("+37.3861-122.0839/")),
        ),
    )

fun webmBytes(): ByteArray = joinBytes(WEBM_HEADER, ByteArray(100))

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun expectImported(outcome: ImportOutcome): ImportedMedia {
    assertTrue("expected an imported item, got $outcome", outcome is ImportOutcome.Imported)
    return (outcome as ImportOutcome.Imported).media
}

class ImportHarness(val pipeline: ImportPipeline, val fileStore: FileStore, val calls: ToolkitCalls)

@RunWith(RobolectricTestRunner::class)
class ImportPipelineTest : EntriesTestBase() {
    private fun importHarness(
        bytes: ByteArray,
        probe: ProbeResult = PHOTO_PROBE,
        modifiedUtc: String? = null,
        config: PublicConfigDto? = testConfig(),
        encryptThumbnails: Boolean = true,
        settingsLoaded: Boolean = true,
        wrapFiles: (EntryFiles) -> EntryFiles = { it },
    ): ImportHarness {
        val fileStore = openFileStore()
        val toolkit = ScriptedToolkit(bytes, probe, modifiedUtc, ThumbnailResult(IMPORT_THUMBNAIL_BYTES, IMPORT_THUMBNAIL_WIDTH, IMPORT_THUMBNAIL_HEIGHT))
        val pipeline = DefaultImportPipeline(
            ImportPipelineDeps(
                sodium = sodium,
                files = wrapFiles(FileStoreEntryFiles { fileStore }),
                toolkit = toolkit,
                clock = TestClock(),
                config = { config },
                deviceSettings = FixedDeviceSettings(settingsWith(encryptThumbnails), settingsLoaded),
                randomId = sequentialIds(),
                ioDispatcher = Dispatchers.IO,
            ),
        )
        return ImportHarness(pipeline, fileStore, toolkit.calls)
    }

    private fun pickerInput(origin: MediaOrigin = MediaOrigin.PICKER, fileName: String? = "a.jpg", pickerExif: JsonObject? = null): MediaInput =
        MediaInput(origin = origin, file = File(context.cacheDir, "a.jpg"), fileName = fileName, pickerExif = pickerExif)

    private fun cameraInput(): MediaInput = pickerInput(origin = MediaOrigin.CAMERA, fileName = null)

    private fun nowTimeWithDeviceOffset(): ResolvedTime = ResolvedTime(FIRST_NOW_ISO, TEST_DEVICE_OFFSET_MINUTES, TimeSource.NOW)

    @Test
    fun writesAnOriginalAndAThumbnailThatDecryptToTheSourceBytes() = runTest {
        val bytes = jpegBytes(tiff = parisTiff(), scanBytes = TWO_AND_A_HALF_MEBIBYTES)
        val harness = importHarness(bytes)
        val input = pickerInput()

        val media = expectImported(harness.pipeline.importOne(input, IMPORT_SLOTS))

        assertEquals(IMPORT_MEDIA_ID, media.mediaId)
        assertEquals(IMPORT_ORIGINAL_OBJECT_ID, media.originalObjectId)
        assertEquals(IMPORT_THUMBNAIL_OBJECT_ID, media.thumbnailObjectId)
        assertEquals(32, media.mediaKey.size)
        assertArrayEquals(
            bytes,
            decryptStoredObject(sodium, harness.fileStore, media.originalObjectId, media.mediaKey, ObjectVariant.ORIGINAL, bytes.size.toLong()),
        )
        assertArrayEquals(
            IMPORT_THUMBNAIL_BYTES,
            decryptStoredObject(sodium, harness.fileStore, media.thumbnailObjectId, media.mediaKey, ObjectVariant.THUMBNAIL, IMPORT_THUMBNAIL_BYTES.size.toLong()),
        )
        assertArrayEquals(IMPORT_THUMBNAIL_BYTES, media.thumbnailBytes)
        assertEquals(listOf(input), harness.calls.discarded.toList())
        assertSame(input, harness.calls.discarded[0])
    }

    @Test
    fun reportsTheCiphertextSizesOfTheStoredObjects() = runTest {
        val bytes = jpegBytes(scanBytes = TWO_AND_A_HALF_MEBIBYTES)
        val harness = importHarness(bytes)

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        val originalLayout = streamLayout(bytes.size.toLong(), STREAM_CHUNK_SIZE_EXPONENT)
        val thumbnailLayout = streamLayout(IMPORT_THUMBNAIL_BYTES.size.toLong(), STREAM_CHUNK_SIZE_EXPONENT)
        assertEquals(originalLayout.ciphertextSize, media.originalCiphertextBytes)
        assertEquals(thumbnailLayout.ciphertextSize, media.thumbnailCiphertextBytes)
        assertEquals(originalLayout.ciphertextSize, harness.fileStore.size(media.originalObjectId))
        assertEquals(thumbnailLayout.ciphertextSize, harness.fileStore.size(media.thumbnailObjectId))
    }

    @Test
    fun startsBothStoredObjectsWithTheKdksMagic() = runTest {
        val harness = importHarness(jpegBytes())

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        for (objectId in listOf(media.originalObjectId, media.thumbnailObjectId)) {
            assertEquals("KDKS", harness.fileStore.readRange(objectId, 0, 4).decodeToString())
        }
    }

    @Test
    fun buildsADescriptorThatValidatesAndCarriesTheFileFacts() = runTest {
        val bytes = jpegBytes(tiff = parisTiff())
        val harness = importHarness(bytes)

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        val schema = ContractSchemas.mediaDescriptorV1
        assertEquals(media.descriptor, schema.decode(schema.encode(media.descriptor)))
        assertEquals(
            MediaDescriptorV1(
                mediaId = IMPORT_MEDIA_ID,
                kind = MediaKind.PHOTO,
                mimeType = "image/jpeg",
                width = 4000,
                height = 3000,
                durationMs = null,
                originalPlaintextBytes = bytes.size.toLong(),
                thumbnail = ThumbnailDescriptor(width = 512, height = 384, plaintextBytes = 100),
                capturedAtUtc = EXIF_CAPTURED_UTC,
                originalFileName = "a.jpg",
            ),
            media.descriptor,
        )
        assertEquals("image/webp", ThumbnailDescriptor.MIME_TYPE)
    }

    @Test
    fun takesTimeAndLocationFromTheExifOfTheFile() = runTest {
        val harness = importHarness(jpegBytes(tiff = parisTiff()), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertEquals(ResolvedTime(EXIF_CAPTURED_UTC, 120, TimeSource.EXIF), media.time)
        assertEquals(LocationSource.EXIF, media.location?.source)
        assertEquals(PARIS.latitude, media.location?.point?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(PARIS.longitude, media.location?.point?.longitude ?: 0.0, COORDINATE_DELTA)
    }

    @Test
    fun hasNoLocationWhenTheFileHasNoGps() = runTest {
        val harness = importHarness(jpegBytes())

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertNull(media.location)
    }

    @Test
    fun fillsOnlyTheFieldsTheFileLeftEmptyFromThePickerExif() = runTest {
        val fileTiff = buildTiff(dateTimeOriginal = EXIF_DATE, offsetTimeOriginal = EXIF_OFFSET)
        val harness = importHarness(jpegBytes(tiff = fileTiff))
        val input = pickerInput(
            pickerExif = buildJsonObject {
                put("DateTimeOriginal", "2020:01:01 00:00:00")
                put("OffsetTimeOriginal", "+00:00")
                put("GPSLatitude", PARIS.latitude)
                put("GPSLatitudeRef", "N")
                put("GPSLongitude", PARIS.longitude)
                put("GPSLongitudeRef", "E")
            },
        )

        val media = expectImported(harness.pipeline.importOne(input, IMPORT_SLOTS))

        assertEquals(EXIF_CAPTURED_UTC, media.time.capturedAtUtc)
        assertEquals(PARIS.latitude, media.location?.point?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(LocationSource.EXIF, media.location?.source)
    }

    @Test
    fun usesTheFileModifiedTimeWhenThereIsNoEmbeddedTime() = runTest {
        val harness = importHarness(jpegBytes(), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertEquals(FILE_MODIFIED_UTC, media.time.capturedAtUtc)
        assertEquals(TimeSource.FILE, media.time.source)
    }

    @Test
    fun usesNowWhenNothingElseGivesATime() = runTest {
        val harness = importHarness(jpegBytes())

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertEquals(nowTimeWithDeviceOffset(), media.time)
    }

    @Test
    fun ignoresEveryEmbeddedFieldForACameraCapture() = runTest {
        val harness = importHarness(jpegBytes(tiff = parisTiff()), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(cameraInput(), IMPORT_SLOTS))

        assertEquals(nowTimeWithDeviceOffset(), media.time)
        assertNull(media.descriptor.originalFileName)
        assertNull(media.location)
    }

    @Test
    fun keepsTheDisplayedProbeSizeForExifOrientation6AndDoesNotRotateAgain() = runTest {
        val harness = importHarness(jpegBytes(tiff = buildTiff(orientation = 6)))

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertEquals(4000L, media.descriptor.width)
        assertEquals(3000L, media.descriptor.height)
    }

    @Test
    fun keepsTheDisplayedProbeSizeForExifOrientation1() = runTest {
        val harness = importHarness(jpegBytes(tiff = buildTiff(orientation = 1)))

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertEquals(4000L, media.descriptor.width)
        assertEquals(3000L, media.descriptor.height)
    }

    @Test
    fun keepsTheDisplayedProbeSizeOfACameraCaptureAndStillIgnoresItsTimeAndGps() = runTest {
        val harness = importHarness(jpegBytes(tiff = buildTiff(orientation = 6, gps = PARIS, dateTimeOriginal = EXIF_DATE)))

        val media = expectImported(harness.pipeline.importOne(cameraInput(), IMPORT_SLOTS))

        assertEquals(4000L, media.descriptor.width)
        assertEquals(3000L, media.descriptor.height)
        assertNull(media.location)
        assertEquals(TimeSource.NOW, media.time.source)
    }

    @Test
    fun keepsTheDisplayedProbeSizeOfAHeifPhotoWithPickerOrientation6() = runTest {
        val harness = importHarness(heicBytes())
        val input = pickerInput(fileName = "a.heic", pickerExif = buildJsonObject { put("Orientation", 6) })

        val media = expectImported(harness.pipeline.importOne(input, IMPORT_SLOTS))

        assertEquals(4000L, media.descriptor.width)
        assertEquals(3000L, media.descriptor.height)
    }

    @Test
    fun readsAHeifPhotoTimeOnlyFromThePickerExif() = runTest {
        val harness = importHarness(heicBytes(), modifiedUtc = FILE_MODIFIED_UTC)
        val input = pickerInput(
            fileName = "a.heic",
            pickerExif = buildJsonObject {
                put("DateTimeOriginal", EXIF_DATE)
                put("OffsetTimeOriginal", EXIF_OFFSET)
            },
        )

        val media = expectImported(harness.pipeline.importOne(input, IMPORT_SLOTS))

        assertEquals("image/heic", media.descriptor.mimeType)
        assertEquals(ResolvedTime(EXIF_CAPTURED_UTC, 120, TimeSource.EXIF), media.time)
    }

    @Test
    fun fallsBackToTheFileTimeForAHeifPhotoWithoutPickerExif() = runTest {
        val harness = importHarness(heicBytes(), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "a.heic"), IMPORT_SLOTS))

        assertEquals(FILE_MODIFIED_UTC, media.time.capturedAtUtc)
        assertEquals(TimeSource.FILE, media.time.source)
    }

    @Test
    fun describesAnMp4VideoWithItsContainerTimeLocationAndDuration() = runTest {
        val harness = importHarness(mp4Bytes(), probe = VIDEO_PROBE)

        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "clip.mp4"), IMPORT_SLOTS))

        val schema = ContractSchemas.mediaDescriptorV1
        assertEquals(media.descriptor, schema.decode(schema.encode(media.descriptor)))
        assertEquals(MediaKind.VIDEO, media.descriptor.kind)
        assertEquals("video/mp4", media.descriptor.mimeType)
        assertEquals(1280L, media.descriptor.width)
        assertEquals(720L, media.descriptor.height)
        assertEquals(5000L, media.descriptor.durationMs)
        assertEquals(EXIF_CAPTURED_UTC, media.descriptor.capturedAtUtc)
        assertEquals("clip.mp4", media.descriptor.originalFileName)
        assertEquals(EXIF_CAPTURED_UTC, media.time.capturedAtUtc)
        assertEquals(TimeSource.CONTAINER, media.time.source)
        assertEquals(37.3861, media.location?.point?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(-122.0839, media.location?.point?.longitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(listOf(1000L), harness.calls.posterTimes.toList())
    }

    @Test
    fun takesThePosterFrameAtHalfTheDurationOfAShortVideo() = runTest {
        val harness = importHarness(mp4Bytes(), probe = ProbeResult(1280, 720, 1500, true, 0))

        expectImported(harness.pipeline.importOne(pickerInput(fileName = "clip.mp4"), IMPORT_SLOTS))

        assertEquals(listOf(750L), harness.calls.posterTimes.toList())
    }

    @Test
    fun readsNoMetadataFromWebmAndUsesTheFileTime() = runTest {
        val harness = importHarness(webmBytes(), probe = ProbeResult(320, 240, 2000, true, 0), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "clip.webm"), IMPORT_SLOTS))

        assertEquals("video/webm", media.descriptor.mimeType)
        assertEquals(FILE_MODIFIED_UTC, media.time.capturedAtUtc)
        assertEquals(TimeSource.FILE, media.time.source)
        assertNull(media.location)
    }

    private suspend fun descriptorSizeFor(rotation: TestRotation): Pair<Long, Long> {
        val harness = importHarness(mp4Bytes(rotation), probe = VIDEO_PROBE)
        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "clip.mp4"), IMPORT_SLOTS))
        return media.descriptor.width to media.descriptor.height
    }

    @Test
    fun swapsWidthAndHeightForA90DegreeRotation() = runTest {
        assertEquals(720L to 1280L, descriptorSizeFor(TestRotation.DEGREES_90))
    }

    @Test
    fun swapsWidthAndHeightForA270DegreeRotation() = runTest {
        assertEquals(720L to 1280L, descriptorSizeFor(TestRotation.DEGREES_270))
    }

    @Test
    fun keepsWidthAndHeightForA0DegreeRotation() = runTest {
        assertEquals(1280L to 720L, descriptorSizeFor(TestRotation.DEGREES_0))
    }

    @Test
    fun keepsWidthAndHeightForA180DegreeRotation() = runTest {
        assertEquals(1280L to 720L, descriptorSizeFor(TestRotation.DEGREES_180))
    }

    @Test
    fun treatsThumbnailsAsEncryptedWhileTheDeviceSettingsAreNotLoaded() = runTest {
        val harness = importHarness(jpegBytes(), encryptThumbnails = false, settingsLoaded = false)

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertNull(harness.fileStore.plainThumbnailFile(media.mediaId))
    }

    @Test
    fun writesAPlainThumbnailWhenThumbnailsAreNotEncrypted() = runTest {
        val harness = importHarness(jpegBytes(), encryptThumbnails = false)

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        val plain = harness.fileStore.plainThumbnailFile(media.mediaId)
        assertNotNull(plain)
        assertArrayEquals(IMPORT_THUMBNAIL_BYTES, plain?.readBytes())
    }

    @Test
    fun writesNoPlainThumbnailWhenThumbnailsAreEncrypted() = runTest {
        val harness = importHarness(jpegBytes())

        val media = expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertNull(harness.fileStore.plainThumbnailFile(media.mediaId))
    }

    @Test
    fun refusesAnUnknownHeaderAsUnsupportedAndWritesNothing() = runTest {
        val harness = importHarness(ByteArray(512) { 7 })

        val outcome = harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.UNSUPPORTED, "a.jpg"), outcome)
        assertEquals(0, harness.fileStore.listSizes().size)
        assertEquals(0, harness.calls.probe.get())
        assertEquals(0, harness.calls.makeThumbnail.get())
        assertEquals(1, harness.calls.discarded.size)
    }

    @Test
    fun refusesAFileTheToolkitCannotDecodeAsUndecodableAndWritesNothing() = runTest {
        val harness = importHarness(heicBytes(), probe = ProbeResult(0, 0, null, false, 0))

        val outcome = harness.pipeline.importOne(pickerInput(fileName = "a.heic"), IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.UNDECODABLE, "a.heic"), outcome)
        assertEquals(0, harness.fileStore.listSizes().size)
        assertEquals(0, harness.calls.makeThumbnail.get())
        assertEquals(1, harness.calls.discarded.size)
    }

    @Test
    fun acceptsAFileWhoseCiphertextIsExactlyTheServerLimitAndRefusesOneByteLess() = runTest {
        val bytes = jpegBytes(scanBytes = 1500)
        val limit = streamLayout(bytes.size.toLong(), STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize

        val atLimit = importHarness(bytes, config = testConfig(maxUploadBytes = limit))
        expectImported(atLimit.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        val below = importHarness(bytes, config = testConfig(maxUploadBytes = limit - 1))
        val outcome = below.pipeline.importOne(pickerInput(), IMPORT_SLOTS)
        assertEquals(ImportOutcome.Refused(ImportRefusal.TOO_LARGE, "a.jpg"), outcome)
        assertEquals(0, below.fileStore.listSizes().size)
        assertEquals(0, below.calls.makeThumbnail.get())
        assertEquals(1, below.calls.discarded.size)
    }

    @Test
    fun usesTheBuiltInFallbackLimitsWhenTheServerConfigIsUnknown() = runTest {
        val harness = importHarness(jpegBytes(), config = null)

        expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))
    }

    @Test
    fun refusesWithTrayFullBeforeReadingAnythingWhenNoSlotIsLeft() = runTest {
        val harness = importHarness(jpegBytes())

        assertEquals(ImportOutcome.Refused(ImportRefusal.TRAY_FULL, "a.jpg"), harness.pipeline.importOne(pickerInput(), ImportSlots(used = 20, limit = 20)))
        val second = harness.pipeline.importOne(pickerInput(), ImportSlots(used = 25, limit = 20))
        assertEquals(ImportRefusal.TRAY_FULL, (second as ImportOutcome.Refused).reason)
        assertEquals(0, harness.calls.byteSource.get())
        assertEquals(0, harness.calls.probe.get())
        assertEquals(2, harness.calls.discarded.size)
        assertEquals(0, harness.fileStore.listSizes().size)
    }

    @Test
    fun reportsANullFileNameForARefusedCameraCapture() = runTest {
        val harness = importHarness(jpegBytes())

        val outcome = harness.pipeline.importOne(cameraInput(), ImportSlots(used = 1, limit = 1))

        assertEquals(ImportOutcome.Refused(ImportRefusal.TRAY_FULL, null), outcome)
    }

    @Test
    fun deletesTheOriginalWhenWritingTheThumbnailFails() = runTest {
        val harness = importHarness(jpegBytes(), wrapFiles = { FailingObjectWrites(it, 2) })
        val input = pickerInput()

        val outcome = harness.pipeline.importOne(input, IMPORT_SLOTS)

        assertEquals(ImportOutcome.Refused(ImportRefusal.READ_FAILED, "a.jpg"), outcome)
        assertEquals(0, harness.fileStore.listSizes().size)
        assertEquals(listOf(input), harness.calls.discarded.toList())
    }

    @Test
    fun leavesNothingBehindWhenWritingTheOriginalFails() = runTest {
        val harness = importHarness(jpegBytes(), wrapFiles = { FailingObjectWrites(it, 1) })

        val outcome = harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS)

        assertEquals(ImportRefusal.READ_FAILED, (outcome as ImportOutcome.Refused).reason)
        assertEquals(0, harness.fileStore.listSizes().size)
        assertEquals(1, harness.calls.discarded.size)
    }

    @Test
    fun deletesBothObjectsWhenWritingThePlainThumbnailFails() = runTest {
        val harness = importHarness(jpegBytes(), encryptThumbnails = false, wrapFiles = { FailingPlainThumbnails(it) })

        val outcome = harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS)

        assertEquals(ImportRefusal.READ_FAILED, (outcome as ImportOutcome.Refused).reason)
        assertEquals(0, harness.fileStore.listSizes().size)
        assertNull(harness.fileStore.plainThumbnailFile(IMPORT_MEDIA_ID))
        assertEquals(1, harness.calls.discarded.size)
    }

    @Test
    fun yieldsToTheCallerBetweenChunks() = runTest {
        val bytes = jpegBytes(scanBytes = TWO_AND_A_HALF_MEBIBYTES)
        val timerFired = CountDownLatch(1)
        launch { timerFired.countDown() }
        val observed = CopyOnWriteArrayList<Boolean>()
        val writes = AtomicInteger(0)
        val harness = importHarness(
            bytes,
            wrapFiles = { inner ->
                object : EntryFiles by inner {
                    override fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long {
                        if (writes.incrementAndGet() != 1) return inner.writeObject(objectId, chunks)
                        return inner.writeObject(objectId, chunks.map { chunk -> chunk.also { observed += timerFired.await(YIELD_WAIT_SECONDS, TimeUnit.SECONDS) } })
                    }
                }
            },
        )

        expectImported(harness.pipeline.importOne(pickerInput(), IMPORT_SLOTS))

        assertTrue(observed.size > 1)
        assertEquals(true, observed.last())
    }

    @Test
    fun storesAnAudioItemWithItsDurationAndTheBundledWaveformThumbnail() = runTest {
        val harness = importHarness(byteArrayOf(1, 2, 3, 4))

        val media = expectImported(harness.pipeline.importVoiceNote(cameraInput(), VoiceNoteInfo(durationMs = 4200, mimeType = "audio/webm"), ImportSlots(used = 0, limit = 20)))

        assertEquals(MediaKind.AUDIO, media.descriptor.kind)
        assertEquals("audio/webm", media.descriptor.mimeType)
        assertEquals(4200L, media.descriptor.durationMs)
        assertEquals(256L, media.descriptor.width)
        assertEquals(256L, media.descriptor.height)
        assertEquals(4L, media.descriptor.originalPlaintextBytes)
        assertEquals("image/webp", ThumbnailDescriptor.MIME_TYPE)
        assertEquals(0, harness.calls.makeThumbnail.get())
        assertEquals(0, harness.calls.probe.get())
        assertEquals(VOICE_THUMBNAIL_SHA256, sha256Hex(media.thumbnailBytes))
        assertEquals(media.thumbnailBytes.size.toLong(), media.descriptor.thumbnail.plaintextBytes)
        assertEquals(256L, media.descriptor.thumbnail.width)
        assertEquals(256L, media.descriptor.thumbnail.height)
        assertEquals(nowTimeWithDeviceOffset(), media.time)
        assertNull(media.location)
        assertNull(media.descriptor.originalFileName)
    }

    @Test
    fun refusesAVoiceNoteWhenTheEntryIsFull() = runTest {
        val harness = importHarness(byteArrayOf(1))

        val outcome = harness.pipeline.importVoiceNote(pickerInput(), VoiceNoteInfo(durationMs = 1000, mimeType = "audio/webm"), ImportSlots(used = 20, limit = 20))

        assertEquals(ImportRefusal.TRAY_FULL, (outcome as ImportOutcome.Refused).reason)
    }

    @Test
    fun readsTimeAndLocationFromTheRealGpsPhotoFixture() = runTest {
        val bytes = fixtureMedia("photo-gps.jpg").readBytes()
        val harness = importHarness(bytes, modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "photo-gps.jpg"), IMPORT_SLOTS))

        assertEquals(ResolvedTime(EXIF_CAPTURED_UTC, 120, TimeSource.EXIF), media.time)
        assertEquals(PARIS.latitude, media.location?.point?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(PARIS.longitude, media.location?.point?.longitude ?: 0.0, COORDINATE_DELTA)
        assertArrayEquals(bytes, decryptStoredObject(sodium, harness.fileStore, media.originalObjectId, media.mediaKey, ObjectVariant.ORIGINAL, bytes.size.toLong()))
    }

    @Test
    fun describesTheRealQuickTimeFixtureWithItsContainerTimeAndLocation() = runTest {
        val harness = importHarness(fixtureMedia("video-gps.mov").readBytes(), probe = ProbeResult(640, 480, 3000, true, 0), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "video-gps.mov"), IMPORT_SLOTS))

        assertEquals(MediaKind.VIDEO, media.descriptor.kind)
        assertEquals("video/quicktime", media.descriptor.mimeType)
        assertEquals(3000L, media.descriptor.durationMs)
        assertEquals(ResolvedTime(EXIF_CAPTURED_UTC, TEST_DEVICE_OFFSET_MINUTES, TimeSource.CONTAINER), media.time)
        assertEquals(PARIS.latitude, media.location?.point?.latitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(PARIS.longitude, media.location?.point?.longitude ?: 0.0, COORDINATE_DELTA)
        assertEquals(listOf(1000L), harness.calls.posterTimes.toList())
    }

    @Test
    fun readsNoMetadataFromTheRealWebmFixture() = runTest {
        val harness = importHarness(fixtureMedia("video-short.webm").readBytes(), probe = ProbeResult(320, 240, 2000, true, 0), modifiedUtc = FILE_MODIFIED_UTC)

        val media = expectImported(harness.pipeline.importOne(pickerInput(fileName = "video-short.webm"), IMPORT_SLOTS))

        assertEquals("video/webm", media.descriptor.mimeType)
        assertEquals(ResolvedTime(FILE_MODIFIED_UTC, TEST_DEVICE_OFFSET_MINUTES, TimeSource.FILE), media.time)
        assertNull(media.location)
    }

    @Test
    fun storesTheRealVoiceFixtureAsAnAudioItem() = runTest {
        val bytes = fixtureMedia("voice-opus.webm").readBytes()
        val harness = importHarness(bytes)

        val media = expectImported(harness.pipeline.importVoiceNote(cameraInput(), VoiceNoteInfo(durationMs = 2000, mimeType = "audio/webm"), IMPORT_SLOTS))

        assertEquals(bytes.size.toLong(), media.descriptor.originalPlaintextBytes)
        assertArrayEquals(bytes, decryptStoredObject(sodium, harness.fileStore, media.originalObjectId, media.mediaKey, ObjectVariant.ORIGINAL, bytes.size.toLong()))
        assertArrayEquals(
            media.thumbnailBytes,
            decryptStoredObject(sodium, harness.fileStore, media.thumbnailObjectId, media.mediaKey, ObjectVariant.THUMBNAIL, media.thumbnailBytes.size.toLong()),
        )
    }
}
