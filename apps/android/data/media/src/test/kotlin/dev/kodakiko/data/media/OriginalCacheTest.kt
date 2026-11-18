package dev.kodakiko.data.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OriginalCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "00112233445566aa"
    private val megabyte = 1_000_000L
    private lateinit var database: KodakikoDatabase
    private lateinit var files: FileStore
    private var cap = 0L
    private var localOnly = false
    private var wifi = true
    private var clock = 1_000L
    private val fetched = mutableListOf<Pair<String, DownloadPriority>>()

    private fun id(prefix: String, n: Int) = (prefix + n).padEnd(22, 'x')

    private fun cache() = OriginalCache(
        database = database,
        files = files,
        capBytes = { cap },
        localOnly = { localOnly },
        isWifi = { wifi },
        fetch = { objectId, priority -> fetched += objectId to priority },
        now = { clock },
    )

    private fun addEntry(n: Int, sizeBytes: Long, upload: ObjectUploadState = ObjectUploadState.UPLOADED, present: Boolean = true, thumbnailBytes: Long = 0): String {
        val entryId = id("e", n)
        val mediaId = id("m", n)
        database.transaction { tx ->
            tx.entries.put(EntryRow(entryId, "owner", EntryPurpose.JOURNAL, 1, ByteArray(1), null, null, null, null, null, "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", EntryLocalState.SYNCED))
            tx.media.put(MediaRow(mediaId, entryId, ByteArray(1)))
            tx.objects.put(ObjectRow(id("o", n), mediaId, ObjectVariant.ORIGINAL, sizeBytes, sizeBytes, if (present) ObjectLocalState.PRESENT else ObjectLocalState.MISSING, upload, null, clock))
            if (thumbnailBytes > 0) tx.objects.put(ObjectRow(id("t", n), mediaId, ObjectVariant.THUMBNAIL, thumbnailBytes, thumbnailBytes, ObjectLocalState.PRESENT, upload, null, clock))
        }
        if (present) files.writeObject(id("o", n), sequenceOf(ByteArray(sizeBytes.toInt())))
        if (thumbnailBytes > 0) files.writeObject(id("t", n), sequenceOf(ByteArray(thumbnailBytes.toInt())))
        clock += 1_000
        return entryId
    }

    private fun state(n: Int): ObjectLocalState = database.transaction { it.objects.get(id("o", n))!!.localState }

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
    fun evictsTheOldestUnpinnedUploadedOriginalsOverTheCapWhilePinnedAndPendingSurvive() {
        for (n in 1..4) addEntry(n, 3 * megabyte)
        addEntry(5, 3 * megabyte, upload = ObjectUploadState.QUEUED)
        val cache = cache()
        cache.pinEntry(id("e", 3))
        cap = 10 * megabyte
        cache.enforceCap()
        assertEquals(ObjectLocalState.MISSING, state(1))
        assertEquals(ObjectLocalState.MISSING, state(2))
        assertEquals(ObjectLocalState.PRESENT, state(3))
        assertEquals(ObjectLocalState.PRESENT, state(4))
        assertEquals(ObjectLocalState.PRESENT, state(5))
        assertNull(files.size(id("o", 1)))
        assertEquals(3 * megabyte, files.size(id("o", 4)))
    }

    @Test
    fun aCapOfZeroMeansNoLimit() {
        addEntry(1, 3 * megabyte)
        cap = 0
        cache().enforceCap()
        assertEquals(ObjectLocalState.PRESENT, state(1))
    }

    @Test
    fun localOnlyModeNeverEvictsNeitherByCapNorByClearing() {
        addEntry(1, 3 * megabyte)
        localOnly = true
        cap = 1
        val cache = cache()
        cache.enforceCap()
        cache.clearOriginals()
        assertEquals(ObjectLocalState.PRESENT, state(1))
    }

    @Test
    fun anOpenOriginalIsNeverEvictedUntilReleased() {
        addEntry(1, 3 * megabyte)
        addEntry(2, 3 * megabyte)
        val cache = cache()
        val release = cache.retain(id("o", 1))
        val second = cache.retain(id("o", 1))
        cap = 1
        cache.enforceCap()
        assertEquals(ObjectLocalState.PRESENT, state(1))
        assertEquals(ObjectLocalState.MISSING, state(2))
        release()
        release()
        cache.enforceCap()
        assertEquals(ObjectLocalState.PRESENT, state(1))
        second()
        cache.enforceCap()
        assertEquals(ObjectLocalState.MISSING, state(1))
    }

    @Test
    fun clearingRemovesEveryEvictableOriginalButKeepsPinnedOpenAndPending() {
        addEntry(1, megabyte)
        addEntry(2, megabyte)
        addEntry(3, megabyte, upload = ObjectUploadState.UPLOADING)
        addEntry(4, megabyte)
        val cache = cache()
        cache.pinEntry(id("e", 2))
        cache.retain(id("o", 4))
        cache.clearOriginals()
        assertEquals(listOf(ObjectLocalState.MISSING, ObjectLocalState.PRESENT, ObjectLocalState.PRESENT, ObjectLocalState.PRESENT), (1..4).map(::state))
    }

    @Test
    fun usageSplitsOriginalsThumbnailsPinnedAndPendingUploadBytes() {
        addEntry(1, 1_000, thumbnailBytes = 100)
        addEntry(2, 2_000, upload = ObjectUploadState.QUEUED, thumbnailBytes = 200)
        addEntry(3, 4_000)
        val cache = cache()
        cache.pinEntry(id("e", 3))
        assertEquals(CacheUsage(originalsBytes = 7_000, thumbnailsBytes = 300, pinnedBytes = 4_000, pendingUploadBytes = 2_200), cache.usage())
    }

    @Test
    fun pinningStoresTheTimeAndDownloadsMissingOriginalsOnlyOnWifi() {
        val wanted = addEntry(1, 1_000, present = false)
        addEntry(2, 1_000, present = false, upload = ObjectUploadState.QUEUED)
        val cache = cache()
        wifi = false
        cache.pinEntry(wanted)
        assertTrue(cache.isPinned(wanted))
        assertEquals(1_000L + 2 * 1_000, database.transaction { it.pins.get(wanted)!!.pinnedAt })
        cache.downloadPinned()
        assertEquals(emptyList<Pair<String, DownloadPriority>>(), fetched)
        wifi = true
        cache.downloadPinned()
        assertEquals(listOf(id("o", 1) to DownloadPriority.BACKGROUND), fetched)
        cache.unpinEntry(wanted)
        assertFalse(cache.isPinned(wanted))
    }

    @Test
    fun originalObjectIdFindsTheOriginalVariant() {
        addEntry(1, 1_000, thumbnailBytes = 10)
        val cache = cache()
        assertEquals(id("o", 1), cache.originalObjectId(id("m", 1)))
        assertNull(cache.originalObjectId(id("m", 9)))
    }

    @Test
    fun downloadPrioritiesKeepTheV031OrderAndWireNames() {
        assertEquals(listOf("opened", "visible", "background"), DownloadPriority.entries.map { it.wire })
    }
}
