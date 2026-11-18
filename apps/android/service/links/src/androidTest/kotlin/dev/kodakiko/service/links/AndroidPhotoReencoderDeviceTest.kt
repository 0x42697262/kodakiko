package dev.kodakiko.service.links

import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.media.MediaContainer
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.media.hasExifSegment
import dev.kodakiko.media.readImageMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPhotoReencoderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    private fun fixture(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }

    private fun snapshot(): Set<String> =
        listOf(target.filesDir, target.cacheDir).flatMap { root -> root.walkTopDown().filter(File::isFile).map { it.path }.toList() }.toSet()

    private fun assertNoExif(bytes: ByteArray) {
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
        assertFalse(hasExifSegment(bytes))
        val metadata = readImageMetadata(byteSourceFromBytes(bytes), MediaContainer.JPEG)
        assertNull(metadata.gps)
        assertNull(metadata.dateTimeOriginal)
        assertNull(metadata.offsetTimeOriginal)
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1600, decoded.width)
        assertEquals(1200, decoded.height)
    }

    @Test
    fun reEncodesTheGpsFixtureIntoAJpegWithoutExifThroughTheRealEncoder() {
        val original = fixture("photo-gps.jpg")
        val source = readImageMetadata(byteSourceFromBytes(original), MediaContainer.JPEG)
        assertNotNull(source.gps)
        assertNotNull(source.dateTimeOriginal)
        val before = snapshot()

        val photo: ReencodedPhoto = runBlocking { AndroidPhotoReencoder().reencode(original, "image/jpeg") }
        assertEquals(1600, photo.width)
        assertEquals(1200, photo.height)
        assertNoExif(photo.bytes)

        val descriptor = MediaDescriptorV1(
            mediaId = "A".repeat(22),
            kind = MediaKind.PHOTO,
            mimeType = "image/jpeg",
            width = 2000,
            height = 1500,
            durationMs = null,
            originalPlaintextBytes = original.size.toLong(),
            thumbnail = ThumbnailDescriptor(width = 512, height = 384, plaintextBytes = 1),
            capturedAtUtc = "2026-05-01T08:00:00.000Z",
            originalFileName = "photo-gps.jpg",
        )
        val stripped: StrippedMedia = runBlocking { DefaultLinkStripper(AndroidPhotoReencoder()).strip(byteSourceFromBytes(original), descriptor) }
        val output = ByteArrayOutputStream().also { stream -> stripped.chunks.forEach { stream.write(it) } }.toByteArray()
        assertEquals("image/jpeg", stripped.mimeType)
        assertEquals(1600L, stripped.width)
        assertEquals(1200L, stripped.height)
        assertNull(stripped.durationMs)
        assertEquals(output.size.toLong(), stripped.plaintextSize)
        assertNoExif(output)
        assertEquals("no temp files may be left", before, snapshot())
        assertEquals(1600, LinksConstants.PHOTO_MAX_EDGE)
        assertEquals(85, LinksConstants.PHOTO_QUALITY)
        assertArrayEquals(fixture("photo-gps.jpg"), original)
    }
}
