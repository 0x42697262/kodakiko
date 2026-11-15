package dev.kodakiko.service.entries

import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.js.JsDate
import dev.kodakiko.media.MIME_JPEG
import dev.kodakiko.media.MIME_MP4
import dev.kodakiko.media.MediaContainer
import dev.kodakiko.media.MediaFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import dev.kodakiko.data.media.MediaKind as PlatformMediaKind

@RunWith(RobolectricTestRunner::class)
class FileMediaToolkitTest : EntriesTestBase() {
    private val probeResult = ProbeResult(640, 480, 3000, true, 0)
    private val thumbnail = ThumbnailResult(byteArrayOf(9, 8, 7), 4, 3)

    private fun inputOf(bytes: ByteArray): MediaInput {
        val file = File.createTempFile("toolkit-", ".bin", context.cacheDir)
        file.writeBytes(bytes)
        return MediaInput(origin = MediaOrigin.PICKER, file = file, fileName = "a.bin", pickerExif = null)
    }

    @Test
    fun readsTheBytesOfTheInputFile() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val toolkit = FileMediaToolkit(FakePlatformMedia(probeResult, thumbnail))
        val input = inputOf(bytes)

        val source = toolkit.byteSource(input)

        assertEquals(5000L, source.size)
        assertArrayEquals(bytes.copyOfRange(100, 164), source.read(100, 64))
        assertArrayEquals(bytes.copyOfRange(4990, 5000), source.read(4990, 64))
        toolkit.discard(input)
    }

    @Test
    fun reportsTheFileModificationTimeAsAnIsoString() {
        val toolkit = FileMediaToolkit(FakePlatformMedia(probeResult, thumbnail))
        val input = inputOf(byteArrayOf(1))
        assertTrue(input.file.setLastModified(JsDate.parse(FILE_MODIFIED_UTC).toLong()))

        assertEquals(FILE_MODIFIED_UTC, toolkit.modifiedUtc(input))
    }

    @Test
    fun reportsNoModificationTimeForAMissingFile() {
        val toolkit = FileMediaToolkit(FakePlatformMedia(probeResult, thumbnail))
        val input = MediaInput(origin = MediaOrigin.PICKER, file = File(context.cacheDir, "missing.jpg"), fileName = "missing.jpg", pickerExif = null)

        assertNull(toolkit.modifiedUtc(input))
    }

    @Test
    fun discardDeletesTheInputFileAndToleratesASecondCall() {
        val toolkit = FileMediaToolkit(FakePlatformMedia(probeResult, thumbnail))
        val input = inputOf(byteArrayOf(1, 2, 3))
        toolkit.byteSource(input).read(0, 3)

        toolkit.discard(input)
        toolkit.discard(input)

        assertFalse(input.file.exists())
    }

    @Test
    fun probesAndThumbnailsThroughThePlatformWithTheMatchingKind() {
        val platform = FakePlatformMedia(probeResult, thumbnail)
        val toolkit = FileMediaToolkit(platform)
        val input = inputOf(byteArrayOf(1))
        val video = MediaFormat(MediaKind.VIDEO, MIME_MP4, MediaContainer.MP4)
        val photo = MediaFormat(MediaKind.PHOTO, MIME_JPEG, MediaContainer.JPEG)

        assertSame(probeResult, toolkit.probe(input, video))
        assertSame(probeResult, toolkit.probe(input, photo))
        assertSame(thumbnail, toolkit.makeThumbnail(input, video, 1000))
        assertSame(thumbnail, toolkit.makeThumbnail(input, photo, 0))

        assertEquals(listOf(input.file to PlatformMediaKind.VIDEO, input.file to PlatformMediaKind.PHOTO), platform.probed.toList())
        assertEquals(
            listOf(Triple(input.file, PlatformMediaKind.VIDEO, 1000L), Triple(input.file, PlatformMediaKind.PHOTO, 0L)),
            platform.thumbnails.toList(),
        )
    }
}
