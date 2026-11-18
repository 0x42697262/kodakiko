package dev.kodakiko.data.media

import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlatformMediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var directory: File

    private fun fixture(name: String): File =
        File(directory, name).also { file -> instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } } }

    private fun snapshot(): Set<String> =
        listOf(target.filesDir, target.cacheDir).flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.path }.toList() }.toSet()

    private fun assertWebp(result: ThumbnailResult, width: Int, height: Int) {
        assertEquals("RIFF", String(result.bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WEBP", String(result.bytes.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals(width, result.width)
        assertEquals(height, result.height)
        val decoded = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
    }

    @Before
    fun createDirectory() {
        directory = File(target.cacheDir, "kodakiko-media-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun probesPhotoAndVideoFixtures() {
        val probe = PlatformProbe(target)
        assertEquals(ProbeResult(2000, 1500, null, true, 0), probe.probe(fixture("photo-gps-base.jpg"), MediaKind.PHOTO))
        assertEquals(ProbeResult(1200, 1600, null, true, 0), probe.probe(fixture("photo-portrait.jpg"), MediaKind.PHOTO))
        assertEquals(ProbeResult(800, 600, null, true, 0), probe.probe(fixture("screenshot.png"), MediaKind.PHOTO))
        assertEquals(ProbeResult(1280, 720, 20_000, true, 0), probe.probe(fixture("video-20s.mp4"), MediaKind.VIDEO))
        assertEquals(ProbeResult(640, 480, 3_000, true, 0), probe.probe(fixture("video-gps.mov"), MediaKind.VIDEO))
        assertEquals(ProbeResult(320, 240, 2_000, true, 0), probe.probe(fixture("video-short.webm"), MediaKind.VIDEO))
    }

    @Test
    fun anUndecodableFileProbesAsUndecodable() {
        val junk = File(directory, "junk.bin").apply { writeBytes(ByteArray(4_096) { (it * 31).toByte() }) }
        val probe = PlatformProbe(target)
        assertEquals(ProbeResult.UNDECODABLE, probe.probe(junk, MediaKind.PHOTO))
        assertEquals(ProbeResult.UNDECODABLE, probe.probe(junk, MediaKind.VIDEO))
        assertEquals(ProbeResult(0, 0, null, false, 0), ProbeResult.UNDECODABLE)
    }

    @Test
    fun photoThumbnailsAreWebpFittedWithin512() {
        val encoder = ThumbnailEncoder(target)
        val inputs = listOf(fixture("photo-gps-base.jpg"), fixture("photo-portrait.jpg"), fixture("screenshot.png"))
        val before = snapshot()
        assertWebp(encoder.photo(inputs[0]), 512, 384)
        assertWebp(encoder.photo(inputs[1]), 384, 512)
        assertWebp(encoder.photo(inputs[2]), 512, 384)
        assertEquals("no temp files may be left", before, snapshot())
        assertEquals(512, MediaConstants.THUMBNAIL_MAX_EDGE)
        assertEquals(80, MediaConstants.THUMBNAIL_QUALITY)
    }

    @Test
    fun aRotatedPhotoGetsAnUprightThumbnail() {
        val rotated = fixture("photo-gps-base.jpg")
        ExifInterface(rotated.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertWebp(ThumbnailEncoder(target).photo(rotated), 384, 512)
    }

    @Test
    fun videoThumbnailsUseTheFrameAtThePosterTime() {
        val encoder = ThumbnailEncoder(target)
        val video = fixture("video-20s.mp4")
        val webm = fixture("video-short.webm")
        val before = snapshot()
        assertWebp(encoder.video(video, 1_000), 512, 288)
        assertWebp(encoder.video(webm, 1_000), 320, 240)
        assertEquals("no temp files may be left", before, snapshot())
        assertEquals(1_000L, posterTimeMs(20_000))
        assertEquals(500L, posterTimeMs(1_000))
        assertEquals(0L, posterTimeMs(null))
    }
}
