package dev.kodakiko.data.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.crypto.createSodium
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class FileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sodium = createSodium()
    private val serverHash = "72a22fb39f638ca4"
    private val validId = "AQIDBAUGBwgJCgsMDQ4PEA"
    private val otherId = "BQYHCAkKCwwNDg8QERITFA"

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }

    private fun installServerFiles(): File {
        File(repoRoot, "tests/fixtures/v031-device/server/files/kodakiko").copyRecursively(File(context.filesDir, "kodakiko"), overwrite = true)
        return File(context.filesDir, "kodakiko/$serverHash")
    }

    @Test
    fun accountHashMatchesV031() {
        assertEquals(serverHash, accountHash(sodium, "10.0.2.2:9580/pufepQc0df0nbPs1EifhgA"))
        assertEquals("b1abbb91a5439ec1", accountHash(sodium, "local/AJodCY5u6JCoGljpGC5X5w"))
    }

    @Test
    fun pathsMatchV031() {
        val paths = AccountPaths(context, serverHash)
        assertEquals(File(context.filesDir, "kodakiko/$serverHash/objects/$validId.kdks"), paths.objectFile(validId))
        assertEquals(File(context.filesDir, "kodakiko/$serverHash/objects/$validId.kdks.partial"), paths.partialFile(validId))
        assertEquals(File(context.filesDir, "kodakiko/$serverHash/thumbs-plain/$validId.webp"), paths.plainThumbnailFile(validId))
        assertEquals(File(context.cacheDir, "kodakiko/$serverHash/tmp"), paths.tempDirectory)
        assertEquals(File(context.filesDir, "SQLite/kodakiko-$serverHash.db"), paths.databaseFile)
        assertThrows(IllegalArgumentException::class.java) { paths.objectFile("../../etc") }
        assertThrows(IllegalArgumentException::class.java) { paths.plainThumbnailFile("short") }
    }

    @Test
    fun theFixtureObjectsAndThumbnailsAreReadInPlace() {
        val root = installServerFiles()
        File(root, "objects/$otherId.kdks.partial").writeBytes(byteArrayOf(1))
        val store = FileStore.open(context, serverHash)
        assertFalse(File(root, "objects/$otherId.kdks.partial").exists())
        val sizes = store.listSizes()
        assertEquals(4, sizes.size)
        assertEquals(6_490_747L, sizes.values.max())
        val small = sizes.entries.first { it.value == 82L }.key
        val head = store.readRange(small, 0, 4)
        assertArrayEquals("KDKS".toByteArray(), head)
        assertEquals(82L, store.size(small))
        assertNull(store.size(validId))
        assertEquals(8, File(root, "thumbs-plain").listFiles()!!.size)
        val thumbnailId = File(root, "thumbs-plain").listFiles()!!.first().name.removeSuffix(".webp")
        assertTrue(store.plainThumbnailFile(thumbnailId)!!.isFile)
        assertNull(store.plainThumbnailFile(validId))
    }

    @Test
    fun writesAreAtomicThroughAPartialFile() {
        val store = FileStore.open(context, "4444444444444444")
        val written = store.writeObject(validId, sequenceOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertEquals(3L, written)
        assertArrayEquals(byteArrayOf(2, 3), store.readRange(validId, 1, 2))
        assertThrows(IOException::class.java) {
            store.writeObject(otherId, sequence {
                yield(byteArrayOf(9))
                throw IOException("source failed")
            })
        }
        assertNull(store.size(otherId))
        assertFalse(store.paths.partialFile(otherId).exists())
        store.writeObject(validId, sequenceOf(byteArrayOf(7)))
        assertArrayEquals(byteArrayOf(7), store.readRange(validId, 0, 1))
        store.delete(validId)
        assertNull(store.size(validId))
        store.writePlainThumbnail(otherId, byteArrayOf(5, 5))
        assertArrayEquals(byteArrayOf(5, 5), store.plainThumbnailFile(otherId)!!.readBytes())
        store.deletePlainThumbnail(otherId)
        assertNull(store.plainThumbnailFile(otherId))
    }

    @Test
    fun sweepDeletesOnlyStaleUnknownFiles() {
        val store = FileStore.open(context, "5555555555555555")
        val ids = listOf("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", "CCCCCCCCCCCCCCCCCCCCCC")
        for (id in ids) {
            store.writeObject(id, sequenceOf(byteArrayOf(1)))
            store.writePlainThumbnail(id, byteArrayOf(1))
        }
        val old = System.currentTimeMillis() - 10 * 60_000
        for (id in ids.take(2)) {
            store.paths.objectFile(id).setLastModified(old)
            store.paths.plainThumbnailFile(id).setLastModified(old)
        }
        File(store.paths.objectFile(ids[0]).parentFile, "notes.txt").apply { writeText("x"); setLastModified(old) }
        store.sweepOrphans(knownObjectIds = setOf(ids[0]), olderThanMs = 60_000, knownMediaIds = setOf(ids[1]))
        assertEquals(setOf(ids[0], ids[2]), store.listSizes().keys)
        assertTrue(File(store.paths.objectFile(ids[0]).parentFile, "notes.txt").exists())
        assertNull(store.plainThumbnailFile(ids[0]))
        assertTrue(store.plainThumbnailFile(ids[1])!!.exists())
        assertTrue(store.plainThumbnailFile(ids[2])!!.exists())
    }

    @Test
    fun destroyRemovesTheAccountDirectoryOnly() {
        val root = installServerFiles()
        val other = FileStore.open(context, "6666666666666666")
        other.writeObject(validId, sequenceOf(byteArrayOf(1)))
        FileStore.open(context, serverHash).destroy()
        assertFalse(root.exists())
        assertEquals(1L, other.size(validId))
    }

    @Test
    fun tempFilesLiveInTheAccountTmpDirectoryAndWipeClearsTheWholeCache() {
        val temp = TempFiles(context, sodium) { serverHash }
        val file = temp.create(TempPurpose.PLAY, "mp4")
        file.write(byteArrayOf(1, 2, 3))
        file.close()
        assertEquals(File(context.cacheDir, "kodakiko/$serverHash/tmp"), file.file.parentFile)
        assertTrue(file.file.name.startsWith("play-") && file.file.name.endsWith(".mp4"))
        assertEquals(3L, file.file.length())
        val view = temp.create(TempPurpose.VIEW, "jpg")
        view.close()
        temp.wipePurpose(TempPurpose.PLAY)
        assertFalse(file.file.exists())
        assertTrue(view.file.exists())
        File(context.cacheDir, "unrelated").writeText("cache of a library")
        temp.wipe()
        assertEquals(0, context.cacheDir.listFiles()!!.size)
        assertThrows(IllegalStateException::class.java) { TempFiles(context, sodium) { null }.create(TempPurpose.IMPORT, "jpg") }
    }
}
