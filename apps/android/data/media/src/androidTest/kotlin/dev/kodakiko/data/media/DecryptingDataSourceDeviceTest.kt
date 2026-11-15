package dev.kodakiko.data.media

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.createStreamEncryptor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DecryptingDataSourceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val sodium = createSodium()
    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private lateinit var plain: ByteArray
    private lateinit var encrypted: File

    private fun snapshot(): Map<String, Long> =
        listOf(target.filesDir, target.cacheDir).flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toList()
        }.toMap()

    private fun factory() = DecryptingDataSource.Factory {
        StreamFileReader.open(sodium, CiphertextSource.of(encrypted), key, plain.size.toLong())
    }

    @Before
    fun encryptFixture() {
        plain = instrumentation.context.assets.open("video-20s.mp4").use { it.readBytes() }
        encrypted = File(target.filesDir, "kodakiko-test/video.kdks").apply { parentFile!!.mkdirs() }
        val encryptor = createStreamEncryptor(sodium, key, plain.size.toLong())
        encrypted.outputStream().buffered().use { out ->
            out.write(encryptor.header)
            var position = 0
            while (!encryptor.isDone()) {
                val length = minOf(encryptor.layout.chunkSize, plain.size - position)
                out.write(encryptor.encryptNext(plain.copyOfRange(position, position + length)))
                position += length
            }
        }
    }

    @After
    fun removeFixture() {
        File(target.filesDir, "kodakiko-test").deleteRecursively()
    }

    @Test
    fun servesExactPlaintextRangesThroughTheDataSourceContract() {
        val uri = Uri.fromFile(encrypted)
        val source = factory().createDataSource()
        assertEquals(5_000L, source.open(DataSpec.Builder().setUri(uri).setPosition(1_000).setLength(5_000).build()))
        val out = ByteArray(5_000)
        var read = 0
        while (read < out.size) read += source.read(out, read, out.size - read).also { assertTrue(it > 0) }
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        assertArrayEquals(plain.copyOfRange(1_000, 6_000), out)
        assertEquals(uri, source.uri)
        source.close()
        assertNull(source.uri)

        val tail = plain.size - 3_000L
        assertEquals(3_000L, source.open(DataSpec.Builder().setUri(uri).setPosition(tail).build()))
        source.close()
        assertThrows(IOException::class.java) { source.open(DataSpec.Builder().setUri(uri).setPosition(plain.size + 1L).build()) }
        source.close()
    }

    @Test
    fun aTamperedChunkSurfacesAsAnIoErrorNotAsBytes() {
        RandomAccessFile(encrypted, "rw").use {
            val at = STREAM_HEADER_BYTES + (1L shl 20) + 16 + 100
            it.seek(at)
            val original = it.read()
            it.seek(at)
            it.write(original xor 0x01)
        }
        val source = factory().createDataSource()
        source.open(DataSpec.Builder().setUri(Uri.fromFile(encrypted)).setPosition((1L shl 20) + 50).setLength(100).build())
        assertThrows(IOException::class.java) { source.read(ByteArray(100), 0, 100) }
        source.close()
    }

    @Test
    fun playsAndSeeksTheFixtureVideoWithoutWritingPlaintext() {
        val before = snapshot()
        lateinit var player: ExoPlayer
        lateinit var texture: SurfaceTexture
        val ready = CountDownLatch(1)
        val afterSeek = CountDownLatch(1)
        var seeking = false
        var seekStarted = 0L
        var seekFrameMs = -1L
        var error: PlaybackException? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(target).build()
            texture = SurfaceTexture(1)
            player.setVideoSurface(Surface(texture))
            player.setMediaSource(ProgressiveMediaSource.Factory(factory()).createMediaSource(MediaItem.fromUri(Uri.fromFile(encrypted))))
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && !seeking) ready.countDown()
                    }

                    override fun onRenderedFirstFrame() {
                        if (seeking && seekFrameMs < 0) {
                            seekFrameMs = (System.nanoTime() - seekStarted) / 1_000_000
                            afterSeek.countDown()
                        }
                    }

                    override fun onPlayerError(playbackError: PlaybackException) {
                        error = playbackError
                        ready.countDown()
                        afterSeek.countDown()
                    }
                },
            )
            player.prepare()
        }
        assertTrue("player never became ready", ready.await(60, TimeUnit.SECONDS))
        assertNull(error)
        var durationMs = 0L
        instrumentation.runOnMainSync {
            durationMs = player.duration
            seeking = true
            seekStarted = System.nanoTime()
            player.seekTo(player.duration * 9 / 10)
            player.play()
        }
        assertTrue("no frame after seek", afterSeek.await(60, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            player.release()
            texture.release()
        }
        assertNull(error)
        assertTrue("duration $durationMs ms", durationMs in 19_500L..20_500L)
        assertTrue("seek to first frame took $seekFrameMs ms (emulator ceiling 1000 ms)", seekFrameMs in 0..1_000)
        val newFiles = snapshot().keys - before.keys
        assertTrue("no new files may appear during playback: $newFiles", newFiles.isEmpty())
    }
}
