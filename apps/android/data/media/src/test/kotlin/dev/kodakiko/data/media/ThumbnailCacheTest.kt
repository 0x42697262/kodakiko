package dev.kodakiko.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ThumbnailCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "00112233445566cc"
    private val sodium = createSodium()
    private lateinit var database: KodakikoDatabase
    private lateinit var files: FileStore
    private var encrypt = false
    private val server = ConcurrentHashMap<String, ByteArray>()
    private val fetches = Collections.synchronizedList(mutableListOf<Pair<String, DownloadPriority>>())
    private val keyRequests = AtomicInteger()
    private val failing = Collections.synchronizedSet(mutableSetOf<String>())

    private fun id(prefix: String, n: Int) = (prefix + n).padEnd(22, 'x')

    private fun keyOf(mediaId: String) = ByteArray(32) { (mediaId.hashCode() + it).toByte() }

    private fun plainOf(n: Int) = ByteArray(2_000 + n) { (it * 7 + n).toByte() }

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

    private fun cache() = ThumbnailCache(
        database = database,
        files = files,
        sodium = sodium,
        variantKey = { _, mediaId ->
            keyRequests.incrementAndGet()
            keyOf(mediaId)
        },
        encryptThumbnails = { encrypt },
        fetch = { objectId, priority ->
            fetches += objectId to priority
            Thread.sleep(50)
            if (objectId in failing) throw IOException("offline")
            files.writeObject(objectId, sequenceOf(server.getValue(objectId)))
            database.transaction { tx -> tx.objects.put(tx.objects.get(objectId)!!.copy(localState = ObjectLocalState.PRESENT)) }
        },
    )

    private data class Thumb(val entryId: String, val mediaId: String, val objectId: String, val plain: ByteArray)

    private fun add(n: Int, local: ObjectLocalState, upload: ObjectUploadState = ObjectUploadState.UPLOADED, trashed: Boolean = false): Thumb {
        val thumb = Thumb(id("e", n), id("m", n), id("t", n), plainOf(n))
        val ciphertext = encrypted(thumb.mediaId, thumb.plain)
        database.transaction { tx ->
            tx.entries.put(EntryRow(thumb.entryId, "owner", EntryPurpose.JOURNAL, 1, ByteArray(1), null, null, null, null, if (trashed) "2026-02-01T00:00:00.000Z" else null, "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", EntryLocalState.SYNCED))
            tx.media.put(MediaRow(thumb.mediaId, thumb.entryId, ByteArray(1)))
            tx.objects.put(ObjectRow(thumb.objectId, thumb.mediaId, ObjectVariant.THUMBNAIL, ciphertext.size.toLong(), thumb.plain.size.toLong(), local, upload, null, 0))
        }
        server[thumb.objectId] = ciphertext
        if (local == ObjectLocalState.PRESENT) files.writeObject(thumb.objectId, sequenceOf(ciphertext))
        return thumb
    }

    private fun state(thumb: Thumb) = database.transaction { it.objects.get(thumb.objectId)!!.localState }

    private fun assertPlainOnly(thumb: Thumb) {
        assertArrayEquals(thumb.plain, files.plainThumbnailFile(thumb.mediaId)!!.readBytes())
        assertNull(files.size(thumb.objectId))
        assertEquals(ObjectLocalState.MISSING, state(thumb))
    }

    @Before
    fun open() {
        database = KodakikoDatabase.open(context, hash)
        files = FileStore.open(context, hash)
    }

    @After
    fun close() {
        database.close()
        files.destroy()
        KodakikoDatabase.databaseFile(context, hash).parentFile!!.listFiles()!!.forEach { it.delete() }
    }

    @Test
    fun aPlainThumbnailFileIsServedAsIs() {
        val thumb = add(1, ObjectLocalState.MISSING)
        files.writePlainThumbnail(thumb.mediaId, thumb.plain)
        val image = cache().get(thumb.entryId, thumb.mediaId)
        assertEquals(ThumbnailImage.PlainFile(files.plainThumbnailFile(thumb.mediaId)!!), image)
        assertEquals(0, keyRequests.get())
        assertTrue(fetches.isEmpty())
    }

    @Test
    fun aPresentEncryptedThumbnailIsDecryptedOnceAndServedFromMemory() {
        encrypt = true
        val thumb = add(1, ObjectLocalState.PRESENT)
        val cache = cache()
        val first = cache.get(thumb.entryId, thumb.mediaId) as ThumbnailImage.Bytes
        assertArrayEquals(thumb.plain, first.bytes)
        assertSame(first, cache.get(thumb.entryId, thumb.mediaId))
        assertEquals(1, keyRequests.get())
        cache.clear()
        cache.get(thumb.entryId, thumb.mediaId)
        assertEquals(2, keyRequests.get())
    }

    @Test
    fun nothingLocalAndNothingDownloadableIsNull() {
        val pending = add(1, ObjectLocalState.MISSING, upload = ObjectUploadState.QUEUED)
        val gone = add(2, ObjectLocalState.UNAVAILABLE)
        val cache = cache()
        assertNull(cache.get(pending.entryId, pending.mediaId))
        assertNull(cache.get(gone.entryId, gone.mediaId))
        assertNull(cache.get(id("e", 9), id("m", 9)))
        assertTrue(fetches.isEmpty())
    }

    @Test
    fun memoryKeepsThreeHundredThumbnailsAndEvictsTheLeastRecentlyUsed() {
        encrypt = true
        val thumbs = (1..301).map { add(it, ObjectLocalState.PRESENT) }
        val cache = cache()
        thumbs.take(300).forEach { cache.get(it.entryId, it.mediaId) }
        cache.get(thumbs[0].entryId, thumbs[0].mediaId)
        cache.get(thumbs[300].entryId, thumbs[300].mediaId)
        assertEquals(301, keyRequests.get())
        cache.get(thumbs[0].entryId, thumbs[0].mediaId)
        assertEquals(301, keyRequests.get())
        cache.get(thumbs[1].entryId, thumbs[1].mediaId)
        assertEquals(302, keyRequests.get())
        assertEquals(300, MediaConstants.THUMBNAIL_MEMORY_CACHE_ITEMS)
    }

    @Test
    fun plainModeDownloadsOnceAndKeepsExactlyOnePlainFile() {
        val thumb = add(1, ObjectLocalState.MISSING)
        val cache = cache()
        val pool = Executors.newFixedThreadPool(3)
        val results = (1..3).map { pool.submit<ThumbnailImage?> { cache.get(thumb.entryId, thumb.mediaId) } }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(listOf(thumb.objectId to DownloadPriority.VISIBLE), fetches.toList())
        assertPlainOnly(thumb)
        results.forEach { assertTrue("$it", it is ThumbnailImage.PlainFile) }
        assertTrue(cache.get(thumb.entryId, thumb.mediaId) is ThumbnailImage.PlainFile)
    }

    @Test
    fun encryptedModeKeepsTheEncryptedObjectOnly() {
        encrypt = true
        val thumb = add(1, ObjectLocalState.MISSING)
        val image = cache().get(thumb.entryId, thumb.mediaId) as ThumbnailImage.Bytes
        assertArrayEquals(thumb.plain, image.bytes)
        assertNull(files.plainThumbnailFile(thumb.mediaId))
        assertEquals(ObjectLocalState.PRESENT, state(thumb))
    }

    @Test
    fun aFailedDownloadIsNullAndCanBeRetried() {
        val thumb = add(1, ObjectLocalState.MISSING)
        val cache = cache()
        failing += thumb.objectId
        assertNull(cache.get(thumb.entryId, thumb.mediaId))
        failing.clear()
        assertNotNull(cache.get(thumb.entryId, thumb.mediaId))
        assertEquals(2, fetches.size)
    }

    @Test
    fun afterUploadDropsTheEncryptedCopyInPlainModeAndKeepsItInEncryptedMode() {
        val fresh = add(1, ObjectLocalState.PRESENT, upload = ObjectUploadState.UPLOADING)
        val withPlain = add(2, ObjectLocalState.PRESENT, upload = ObjectUploadState.UPLOADING)
        files.writePlainThumbnail(withPlain.mediaId, withPlain.plain)
        val cache = cache()
        encrypt = true
        cache.afterUpload(fresh.objectId)
        assertEquals(server.getValue(fresh.objectId).size.toLong(), files.size(fresh.objectId))
        encrypt = false
        cache.afterUpload(fresh.objectId)
        assertPlainOnly(fresh)
        cache.afterUpload(withPlain.objectId)
        assertPlainOnly(withPlain)
        cache.afterUpload(id("t", 9))
    }

    @Test
    fun prefetchAllDownloadsOnlyMissingUploadedThumbnailsOfNonTrashedEntries() {
        val kept = add(1, ObjectLocalState.MISSING)
        add(2, ObjectLocalState.MISSING, trashed = true)
        add(3, ObjectLocalState.MISSING, upload = ObjectUploadState.QUEUED)
        add(4, ObjectLocalState.PRESENT)
        val plain = add(5, ObjectLocalState.MISSING)
        files.writePlainThumbnail(plain.mediaId, plain.plain)
        val failed = add(6, ObjectLocalState.MISSING)
        failing += failed.objectId
        cache().prefetchAll()
        assertEquals(setOf(kept.objectId to DownloadPriority.BACKGROUND, failed.objectId to DownloadPriority.BACKGROUND), fetches.toSet())
        assertPlainOnly(kept)
    }

    @Test
    fun switchingModesInBothDirectionsLeavesOneCopyOfTheRightKindAndReportsProgress() {
        val thumbs = (1..3).map { add(it, ObjectLocalState.MISSING) }
        val cache = cache()
        thumbs.forEach { cache.get(it.entryId, it.mediaId) }
        val seen = Collections.synchronizedList(mutableListOf<ThumbnailModeSwitch>())
        val unsubscribe = cache.subscribeModeSwitch { seen += it }
        encrypt = true
        cache.switchMode(true)
        thumbs.forEach {
            assertNull(files.plainThumbnailFile(it.mediaId))
            assertEquals(ObjectLocalState.PRESENT, state(it))
        }
        assertEquals(
            listOf(ThumbnailModeSwitch(true, 0, 3), ThumbnailModeSwitch(true, 1, 3), ThumbnailModeSwitch(true, 2, 3), ThumbnailModeSwitch(true, 3, 3), ThumbnailModeSwitch.IDLE),
            seen.toList(),
        )
        assertTrue(cache.get(thumbs[0].entryId, thumbs[0].mediaId) is ThumbnailImage.Bytes)
        seen.clear()
        encrypt = false
        cache.switchMode(false)
        thumbs.forEach(::assertPlainOnly)
        assertEquals(ThumbnailModeSwitch(true, 0, 3), seen.first())
        assertEquals(ThumbnailModeSwitch.IDLE, seen.last())
        assertEquals(ThumbnailModeSwitch.IDLE, cache.modeSwitch())
        assertTrue(cache.get(thumbs[0].entryId, thumbs[0].mediaId) is ThumbnailImage.PlainFile)
        unsubscribe()
    }
}
