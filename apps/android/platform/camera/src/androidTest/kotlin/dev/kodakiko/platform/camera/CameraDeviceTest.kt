package dev.kodakiko.platform.camera

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CameraDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var owner: TestOwner
    private lateinit var camera: CameraSession

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun bind(facing: CameraFacing): Boolean {
        val done = CountDownLatch(1)
        var bound = false
        main {
            camera.bind(facing, preview = null) { result ->
                bound = result
                done.countDown()
            }
        }
        assertTrue("bind never answered", done.await(20, TimeUnit.SECONDS))
        return bound
    }

    private fun tracks(file: File): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
        } finally {
            extractor.release()
        }
    }

    private fun record(maxFileSizeBytes: Long, stopAfterMs: Long?): VideoResult {
        val finished = CountDownLatch(1)
        var result: VideoResult? = null
        val output = File(directory, "video.mp4")
        main {
            camera.startRecording(output, maxFileSizeBytes, withAudio = true) { outcome ->
                result = outcome
                finished.countDown()
            }
        }
        if (stopAfterMs != null) {
            Thread.sleep(stopAfterMs)
            var recording = false
            main { recording = camera.isRecording() }
            assertTrue(recording)
            main { camera.stopRecording() }
        }
        assertTrue("recording never finished", finished.await(30, TimeUnit.SECONDS))
        return result!!
    }

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.CAMERA)
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.RECORD_AUDIO)
        directory = File(target.cacheDir, "kodakiko-camera-test").apply {
            deleteRecursively()
            mkdirs()
        }
        main {
            owner = TestOwner()
            owner.registry.currentState = Lifecycle.State.RESUMED
            camera = CameraSession(target, owner)
        }
    }

    @After
    fun tearDown() {
        main {
            camera.release()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        directory.deleteRecursively()
    }

    @Test
    fun theBackCameraBindsAndAMissingFacingDoesNot() {
        val facings = CameraSession.availableFacings(target)
        assertTrue(CameraFacing.BACK in facings)
        assertTrue(bind(CameraFacing.BACK))
        if (CameraFacing.FRONT !in facings) assertFalse(bind(CameraFacing.FRONT))
    }

    @Test
    fun takesAJpegPhoto() {
        assertTrue(bind(CameraFacing.BACK))
        val done = CountDownLatch(1)
        var result: PhotoResult? = null
        val output = File(directory, "photo.jpg")
        main {
            camera.takePhoto(output) {
                result = it
                done.countDown()
            }
        }
        assertTrue("photo never finished", done.await(30, TimeUnit.SECONDS))
        assertEquals(PhotoResult.Saved(output), result)
        val bytes = output.readBytes()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
    }

    @Test
    fun recordsAnMp4WithVideoAndAudio() {
        assertTrue(bind(CameraFacing.BACK))
        val result = record(maxFileSizeBytes = 0, stopAfterMs = 2_000) as VideoResult.Saved
        assertFalse(result.reachedSizeLimit)
        assertTrue(tracks(result.file).any { it.startsWith("video/") })
        assertTrue(tracks(result.file).any { it.startsWith("audio/") })
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(result.file.path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("duration $durationMs", durationMs in 1_000L..4_000L)
        }
    }

    @Test
    fun aRecordingStopsItselfAtTheSizeCap() {
        assertTrue(bind(CameraFacing.BACK))
        val cap = 1_000_000L
        val result = record(maxFileSizeBytes = cap, stopAfterMs = null) as VideoResult.Saved
        assertTrue(result.reachedSizeLimit)
        assertTrue("size ${result.file.length()}", result.file.length() in 1L..cap)
    }

    @Test
    fun aCapTooSmallForTheContainerFailsAtOnceWithoutAFile() {
        assertTrue(bind(CameraFacing.BACK))
        val result = record(maxFileSizeBytes = CameraConstants.MUXER_RESERVED_BYTES, stopAfterMs = null)
        assertTrue("$result", result is VideoResult.Failed)
        assertFalse(File(directory, "video.mp4").exists())
    }

    @Test
    fun stoppingWhenNotRecordingDoesNothing() {
        assertTrue(bind(CameraFacing.BACK))
        main {
            assertFalse(camera.isRecording())
            camera.stopRecording()
        }
    }
}
