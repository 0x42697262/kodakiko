package dev.kodakiko.platform.audio

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private lateinit var directory: File

    @Before
    fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.RECORD_AUDIO)
        directory = File(target.cacheDir, "kodakiko-audio-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun audioTrack(file: File): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return (0 until extractor.trackCount).map(extractor::getTrackFormat).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        } finally {
            extractor.release()
        }
    }

    @Test
    fun recordsAacInMp4WithTheV031Preset() {
        val recorder = VoiceRecorder(target)
        val output = File(directory, "voice.m4a")
        assertFalse(recorder.isRecording())
        recorder.start(output)
        assertTrue(recorder.isRecording())
        Thread.sleep(1_500)
        assertTrue(recorder.elapsedMs() in 1_000L..3_000L)
        val recorded = recorder.stop()!!
        assertFalse(recorder.isRecording())
        assertEquals(output, recorded.file)
        assertEquals("audio/mp4", recorded.mimeType)
        assertTrue("duration ${recorded.durationMs}", recorded.durationMs in 1_200L..3_000L)
        val track = audioTrack(output)
        assertEquals("audio/mp4a-latm", track.getString(MediaFormat.KEY_MIME))
        assertEquals(44_100, track.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            assertEquals("audio/mp4", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            val fileMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("file duration $fileMs", fileMs in 1_000L..3_000L)
        }
        assertEquals(600_000L, AudioConstants.VOICE_MAX_MS)
        assertEquals(44_100, AudioConstants.SAMPLE_RATE)
        assertEquals(128_000, AudioConstants.BIT_RATE)
    }

    @Test
    fun stoppingWithoutRecordingIsNull() {
        assertNull(VoiceRecorder(target).stop())
    }

    @Test
    fun theRecordingStopsItselfAtTheMaximumDuration() {
        val reached = CountDownLatch(1)
        val recorder = VoiceRecorder(target, maxDurationMs = 1_000, onMaxReached = { reached.countDown() })
        val output = File(directory, "capped.m4a")
        recorder.start(output)
        assertTrue("max duration was never reached", reached.await(10, TimeUnit.SECONDS))
        val recorded = recorder.stop()!!
        assertTrue("duration ${recorded.durationMs}", recorded.durationMs in 800L..2_000L)
        assertTrue(output.length() > 0)
    }

    private fun playThrough(file: File, expectedMs: LongRange) {
        lateinit var player: VoicePlayer
        val ready = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            player = VoicePlayer(target)
            player.subscribe { state ->
                if (state.error != null) {
                    failure = state.error
                    ready.countDown()
                    ended.countDown()
                }
                if (state.ready) ready.countDown()
                if (state.ended) ended.countDown()
            }
            player.load(DefaultDataSource.Factory(target), Uri.fromFile(file))
        }
        assertTrue("never ready", ready.await(20, TimeUnit.SECONDS))
        assertNull(failure)
        var durationMs = 0L
        instrumentation.runOnMainSync {
            durationMs = player.durationMs()
            player.play()
        }
        assertTrue("duration $durationMs", durationMs in expectedMs)
        assertTrue("never ended", ended.await(20, TimeUnit.SECONDS))
        assertNull(failure)
        instrumentation.runOnMainSync {
            assertFalse(player.isPlaying())
            player.seekTo(0)
            player.release()
        }
    }

    @Test
    fun playsARecordedVoiceNote() {
        val recorder = VoiceRecorder(target)
        val output = File(directory, "roundtrip.m4a")
        recorder.start(output)
        Thread.sleep(1_200)
        recorder.stop()
        playThrough(output, 800L..3_000L)
    }

    @Test
    fun playsAWebVoiceNoteInWebmOpus() {
        val webm = File(directory, "voice-opus.webm")
        instrumentation.context.assets.open("voice-opus.webm").use { input -> webm.outputStream().use { input.copyTo(it) } }
        playThrough(webm, 1_900L..2_100L)
    }
}
