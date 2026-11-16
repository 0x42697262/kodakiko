package dev.kodakiko.ui.feature.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.createStreamEncryptor
import dev.kodakiko.data.media.CiphertextSource
import dev.kodakiko.data.media.DecryptingDataSource
import dev.kodakiko.data.media.StreamFileReader
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.theme.KodakikoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class VideoPlayerDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val sodium = createSodium()
    private val key = ByteArray(KEY_BYTES) { (it * 7 + 3).toByte() }
    private val bytesRead = AtomicLong()
    private val opens = AtomicInteger()
    private var plainSize = 0L
    private lateinit var encrypted: File

    private fun snapshot(): Set<String> =
        listOf(target.filesDir, target.cacheDir).flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.path }.toList() }.toSet()

    @Before
    fun encryptFixture() {
        val plain = instrumentation.context.assets.open("video-20s.mp4").use { it.readBytes() }
        plainSize = plain.size.toLong()
        encrypted = File(target.filesDir, "kodakiko-viewer-test/video.kdks").apply { parentFile!!.mkdirs() }
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
        File(target.filesDir, "kodakiko-viewer-test").deleteRecursively()
    }

    private fun prepared(): PreparedVideo {
        val decrypting = DecryptingDataSource.Factory {
            StreamFileReader.open(sodium, CiphertextSource.of(encrypted), key, plainSize)
        }
        return object : PreparedVideo {
            override val uri: Uri = Uri.fromFile(encrypted)

            override val dataSourceFactory: DataSource.Factory = DataSource.Factory { Counting(decrypting.createDataSource()) }

            override fun release() = Unit
        }
    }

    private inner class Counting(private val inner: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            return inner.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }

        override fun getUri(): Uri? = inner.uri

        override fun close() = inner.close()
    }

    @Test
    fun theFullScreenPlayerPlaysTheFixtureThroughTheDecryptingSourceWithoutWritingPlaintext() {
        val before = snapshot()
        val actions = mutableListOf<VideoPlayerAction>()
        val state = VideoPlayerState(progress = 1f, video = prepared(), problem = null)
        compose.setContent {
            KodakikoTheme { VideoPlayerContent(state = state, onAction = { synchronized(actions) { actions += it } }) }
        }
        compose.waitUntil(PLAY_WAIT_MS) { bytesRead.get() >= MIN_BYTES_READ }
        assertTrue(compose.onAllNodes(hasTestTag(TestTags.Video.PLAYER), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        assertEquals(emptyList<VideoPlayerAction>(), synchronized(actions) { actions.toList() })
        assertTrue(opens.get() >= 1)
        assertEquals(before, snapshot())
    }

    private companion object {
        const val KEY_BYTES = 32
        const val PLAY_WAIT_MS = 15_000L
        const val SETTLE_MS = 3_000L
        const val MIN_BYTES_READ = 256L * 1024L
    }
}
