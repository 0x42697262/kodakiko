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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OriginalReleaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "00112233445566bb"
    private val entryId = "entry".padEnd(22, 'x')
    private val mediaId = "media".padEnd(22, 'x')
    private val objectId = "object".padEnd(22, 'x')
    private lateinit var database: KodakikoDatabase
    private lateinit var files: FileStore

    private fun cache() = OriginalCache(
        database = database,
        files = files,
        capBytes = { 1 },
        localOnly = { false },
        isWifi = { false },
        fetch = { _, _ -> },
        now = { 1_000 },
    )

    private fun state(): ObjectLocalState = database.transaction { it.objects.get(objectId)!!.localState }

    @Before
    fun open() {
        database = KodakikoDatabase.open(context, hash)
        files = FileStore.open(context, hash)
        database.transaction { tx ->
            tx.entries.put(EntryRow(entryId, "owner", EntryPurpose.JOURNAL, 1, ByteArray(1), null, null, null, null, null, "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z", EntryLocalState.SYNCED))
            tx.media.put(MediaRow(mediaId, entryId, ByteArray(1)))
            tx.objects.put(ObjectRow(objectId, mediaId, ObjectVariant.ORIGINAL, SIZE, SIZE, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED, null, 1_000))
        }
        files.writeObject(objectId, sequenceOf(ByteArray(SIZE.toInt())))
    }

    @After
    fun close() {
        database.close()
        files.destroy()
        KodakikoDatabase.databaseFile(context, hash).parentFile!!.listFiles()!!.forEach { it.delete() }
    }

    @Test
    fun releaseAllLetsTheCapEvictOriginalsThatWereStillOpen() {
        val cache = cache()
        val handle = cache.open(mediaId)
        assertNotNull(handle)
        cache.retain(objectId)
        cache.enforceCap()
        assertEquals(ObjectLocalState.PRESENT, state())

        cache.releaseAll()
        cache.enforceCap()

        assertEquals(ObjectLocalState.MISSING, state())
        assertNull(files.size(objectId))
    }

    private companion object {
        const val SIZE = 4_096L
    }
}
