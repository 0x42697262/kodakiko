package dev.kodakiko.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.executeBlocking
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.createStreamEncryptor
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageFetchersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "00112233445566dd"
    private val sodium = createSodium()
    private val pool = Executors.newSingleThreadExecutor()
    private lateinit var database: KodakikoDatabase
    private lateinit var files: FileStore
    private lateinit var thumbnails: ThumbnailCache
    private lateinit var loader: ImageLoader
    private var encrypt = false
    private var closes = 0
    private var originalFile: File? = null
    private var originalSize = 0L

    private fun id(prefix: String, n: Int) = (prefix + n).padEnd(22, 'x')

    private fun keyOf(mediaId: String) = ByteArray(32) { (mediaId.hashCode() + it).toByte() }

    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 40, 90))
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    private fun encrypted(mediaId: String, plain: ByteArray): ByteArray {
        val encryptor = createStreamEncryptor(sodium, keyOf(mediaId), plain.size.toLong())
        var out = encryptor.header
        var position = 0
        while (!encryptor.isDone()) {
            val length = minOf(encryptor.layout.chunkSize, plain.size - position)
            out += encryptor.encryptNext(plain.copyOfRange(position, position + length))
            position += length
        }
        return out
    }

    private fun addThumbnail(n: Int, plain: ByteArray, local: ObjectLocalState): Pair<String, String> {
        val entryId = id("e", n)
        val mediaId = id("m", n)
        val ciphertext = encrypted(mediaId, plain)
        database.transaction { tx ->
            tx.entries.put(EntryRow(entryId, "owner", EntryPurpose.JOURNAL, 1, ByteArray(1), null, null, null, null, null, "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", EntryLocalState.SYNCED))
            tx.media.put(MediaRow(mediaId, entryId, ByteArray(1)))
            tx.objects.put(ObjectRow(id("t", n), mediaId, ObjectVariant.THUMBNAIL, ciphertext.size.toLong(), plain.size.toLong(), local, ObjectUploadState.UPLOADED, null, 0))
        }
        if (local == ObjectLocalState.PRESENT) files.writeObject(id("t", n), sequenceOf(ciphertext))
        return entryId to mediaId
    }

    private fun noisyImage(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        bitmap.setPixels(IntArray(width * height) { random.nextInt() or (0xFF shl 24) }, 0, width, 0, 0, width, height)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    private fun writeOriginal(plain: ByteArray) {
        val mediaId = id("m", 50)
        originalFile = File(files.paths.objectFile(id("o", 50)).path).also { it.writeBytes(encrypted(mediaId, plain)) }
        originalSize = plain.size.toLong()
    }

    private fun load(data: Any): ImageResult {
        val request = ImageRequest.Builder(context).data(data).build()
        val future = pool.submit<ImageResult> { loader.executeBlocking(request) }
        while (!future.isDone) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return future.get(1, TimeUnit.SECONDS)
    }

    private fun snapshot(): Map<String, Long> =
        listOf(context.filesDir, context.cacheDir).flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toList() }.toMap()

    @Before
    fun open() {
        database = KodakikoDatabase.open(context, hash)
        files = FileStore.open(context, hash)
        thumbnails = ThumbnailCache(database, files, sodium, { _, mediaId -> keyOf(mediaId) }, { encrypt }, { _, _ -> error("no network in this test") })
        loader = ImageLoaders.create(context, thumbnails) { request ->
            val file = originalFile ?: return@create null
            if (request.mediaId != id("m", 50)) return@create null
            OpenedOriginal(
                reader = dev.kodakiko.data.media.StreamFileReader.open(sodium, CiphertextSource.of(file), keyOf(request.mediaId), originalSize),
                mimeType = request.mimeType,
                onClose = { closes++ },
            )
        }
    }

    @After
    fun close() {
        pool.shutdownNow()
        database.close()
        files.destroy()
        KodakikoDatabase.databaseFile(context, hash).parentFile!!.listFiles()!!.forEach { it.delete() }
    }

    @Test
    fun theLoaderHasNoDiskCacheAndNoNetwork() {
        assertNull(loader.diskCache)
        val result = load("https://example.com/a.png")
        assertTrue("$result", result is ErrorResult)
    }

    @Test
    fun aPlainThumbnailFileLoadsFromDisk() {
        val (entryId, mediaId) = addThumbnail(1, image(64, 48, Bitmap.CompressFormat.PNG), ObjectLocalState.MISSING)
        files.writePlainThumbnail(mediaId, image(64, 48, Bitmap.CompressFormat.PNG))
        val result = load(ThumbnailImageRequest(entryId, mediaId)) as SuccessResult
        assertEquals(64, result.image.width)
        assertEquals(48, result.image.height)
        assertEquals(DataSource.DISK, result.dataSource)
    }

    @Test
    fun anEncryptedThumbnailLoadsFromDecryptedMemory() {
        encrypt = true
        val (entryId, mediaId) = addThumbnail(2, image(32, 32, Bitmap.CompressFormat.WEBP_LOSSY), ObjectLocalState.PRESENT)
        val result = load(ThumbnailImageRequest(entryId, mediaId)) as SuccessResult
        assertEquals(32, result.image.width)
        assertEquals(DataSource.MEMORY, result.dataSource)
    }

    @Test
    fun aMissingThumbnailIsAnUnavailableError() {
        val (entryId, mediaId) = addThumbnail(3, image(8, 8, Bitmap.CompressFormat.PNG), ObjectLocalState.UNAVAILABLE)
        val result = load(ThumbnailImageRequest(entryId, mediaId)) as ErrorResult
        assertTrue("${result.throwable}", result.throwable is MediaUnavailableException)
    }

    @Test
    fun anOriginalDecodesStraightFromTheEncryptedFileWithoutWritingPlaintext() {
        writeOriginal(image(400, 300, Bitmap.CompressFormat.JPEG))
        val before = snapshot()
        val result = load(OriginalImageRequest(id("e", 50), id("m", 50), "image/jpeg")) as SuccessResult
        assertEquals(400, result.image.width)
        assertEquals(300, result.image.height)
        assertEquals(1, closes)
        assertEquals(before, snapshot())
        val missing = load(OriginalImageRequest(id("e", 51), id("m", 51), "image/jpeg")) as ErrorResult
        assertTrue("${missing.throwable}", missing.throwable is MediaUnavailableException)
    }

    @Test
    fun aTamperedOriginalFailsAndStillReleasesTheHandle() {
        val plain = noisyImage(1600, 1200)
        assertTrue("the tampered chunk must not be the final one", plain.size > (1 shl 20) + 1)
        writeOriginal(plain)
        RandomAccessFile(originalFile!!, "rw").use {
            it.seek(STREAM_HEADER_BYTES + 5L)
            val byte = it.read()
            it.seek(STREAM_HEADER_BYTES + 5L)
            it.write(byte xor 0x01)
        }
        val result = load(OriginalImageRequest(id("e", 50), id("m", 50), "image/jpeg"))
        assertTrue("$result", result is ErrorResult)
        assertEquals(1, closes)
    }

    @Test
    fun clearingOnLockEmptiesTheMemoryCache() {
        encrypt = true
        val (entryId, mediaId) = addThumbnail(4, image(16, 16, Bitmap.CompressFormat.PNG), ObjectLocalState.PRESENT)
        load(ThumbnailImageRequest(entryId, mediaId))
        assertTrue(loader.memoryCache!!.size > 0)
        ImageLoaders.clearMemory(loader)
        assertEquals(0L, loader.memoryCache!!.size)
    }
}
