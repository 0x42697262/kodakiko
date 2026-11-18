package dev.kodakiko.service.entries

import dev.kodakiko.data.files.FileStore
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.media.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

const val TRAY_KEY_LENGTH = 32
val TRAY_THUMBNAIL_BYTES = byteArrayOf(1, 2, 3, 4)

@RunWith(RobolectricTestRunner::class)
class CaptureTrayTest : EntriesTestBase() {
    private lateinit var fileStore: FileStore
    private lateinit var files: EntryFiles

    @Before
    fun openStore() {
        fileStore = openFileStore()
        files = FileStoreEntryFiles { fileStore }
    }

    private fun idFor(prefix: String, number: Int): String = prefix + number.toString().padStart(22 - prefix.length, '0')

    private fun storedItem(number: Int, withPlainThumbnail: Boolean = true): ImportedMedia {
        val mediaId = idFor("m", number)
        val originalObjectId = idFor("o", number)
        val thumbnailObjectId = idFor("t", number)
        fileStore.writeObject(originalObjectId, sequenceOf(byteArrayOf(9, 9, 9)))
        fileStore.writeObject(thumbnailObjectId, sequenceOf(byteArrayOf(9, 9, 9)))
        if (withPlainThumbnail) fileStore.writePlainThumbnail(mediaId, TRAY_THUMBNAIL_BYTES)
        return ImportedMedia(
            mediaId = mediaId,
            mediaKey = ByteArray(TRAY_KEY_LENGTH) { number.toByte() },
            originalObjectId = originalObjectId,
            thumbnailObjectId = thumbnailObjectId,
            originalCiphertextBytes = 43,
            thumbnailCiphertextBytes = 43,
            descriptor = fixtureDescriptor(mediaId, 3),
            time = nowTime(),
            location = null,
            thumbnailBytes = TRAY_THUMBNAIL_BYTES,
        )
    }

    private fun tray(isUnlocked: () -> Boolean = { true }): CaptureTray = CaptureTray(CaptureTrayDeps(files = files, isUnlocked = isUnlocked))

    private fun idsOf(items: List<ImportedMedia>): List<String> = items.map { it.mediaId }

    @Test
    fun startsEmptyWithNoDeviceLocation() {
        val tray = tray()

        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertNull(tray.deviceLocation)
    }

    @Test
    fun addsItemsInOrder() {
        val tray = tray()
        val first = storedItem(1)
        val second = storedItem(2)

        tray.add(first)
        tray.add(second)

        assertEquals(listOf(first.mediaId, second.mediaId), idsOf(tray.items))
    }

    @Test
    fun replacesTheItemsListOnEveryChangeSoEarlierSnapshotsStayUnchanged() {
        val tray = tray()
        tray.add(storedItem(1))
        val snapshot = tray.items

        tray.add(storedItem(2))

        assertEquals(1, snapshot.size)
        assertNotSame(snapshot, tray.items)
        assertEquals(2, tray.items.size)
    }

    @Test
    fun removesAnItemAndDeletesBothObjectsAndThePlainThumbnail() = runTest {
        val tray = tray()
        val first = storedItem(1)
        val second = storedItem(2)
        tray.add(first)
        tray.add(second)

        tray.remove(first.mediaId)

        assertEquals(listOf(second.mediaId), idsOf(tray.items))
        assertNull(fileStore.size(first.originalObjectId))
        assertNull(fileStore.size(first.thumbnailObjectId))
        assertNull(fileStore.plainThumbnailFile(first.mediaId))
        assertNotNull(fileStore.size(second.originalObjectId))
        assertNotNull(fileStore.size(second.thumbnailObjectId))
        assertNotNull(fileStore.plainThumbnailFile(second.mediaId))
        assertTrue(isWiped(first.mediaKey))
        assertFalse(isWiped(second.mediaKey))
    }

    @Test
    fun removesAnItemThatHasNoPlainThumbnail() = runTest {
        val tray = tray()
        val only = storedItem(1, withPlainThumbnail = false)
        tray.add(only)

        tray.remove(only.mediaId)

        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertNull(fileStore.size(only.originalObjectId))
    }

    private fun reorderNames(from: Int, to: Int): List<String> {
        val tray = tray()
        val items = listOf(storedItem(1), storedItem(2), storedItem(3))
        val names = items.mapIndexed { index, item -> item.mediaId to listOf("a", "b", "c")[index] }.toMap()
        items.forEach(tray::add)

        tray.reorder(from, to)

        return idsOf(tray.items).map { names.getValue(it) }
    }

    @Test
    fun movesTheItemAt0ToPosition2() {
        assertEquals(listOf("b", "c", "a"), reorderNames(0, 2))
    }

    @Test
    fun movesTheItemAt2ToPosition0() {
        assertEquals(listOf("c", "a", "b"), reorderNames(2, 0))
    }

    @Test
    fun movesTheItemAt0ToPosition1() {
        assertEquals(listOf("b", "a", "c"), reorderNames(0, 1))
    }

    @Test
    fun movesTheItemAt1ToPosition1() {
        assertEquals(listOf("a", "b", "c"), reorderNames(1, 1))
    }

    @Test
    fun ignoresAMoveOutsideTheList() {
        assertEquals(listOf("a", "b", "c"), reorderNames(0, 3))
        assertEquals(listOf("a", "b", "c"), reorderNames(3, 0))
        assertEquals(listOf("a", "b", "c"), reorderNames(0, -1))
    }

    @Test
    fun clearsTheListAndDeletesEveryFileWhenAskedTo() = runTest {
        val tray = tray()
        val items = listOf(storedItem(1), storedItem(2))
        items.forEach(tray::add)
        tray.setDeviceLocation(DeviceLocation(GeoPoint(1.0, 2.0), null))

        tray.clear(true)

        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertEquals(0, fileStore.listSizes().size)
        for (item in items) assertNull(fileStore.plainThumbnailFile(item.mediaId))
        for (item in items) assertTrue(isWiped(item.mediaKey))
        assertNull(tray.deviceLocation)
    }

    @Test
    fun clearsTheListButKeepsTheFilesWhenTheyNowBelongToAnEntry() = runTest {
        val tray = tray()
        val items = listOf(storedItem(1), storedItem(2))
        items.forEach(tray::add)

        tray.clear(false)

        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertEquals(4, fileStore.listSizes().size)
        for (item in items) assertNotNull(fileStore.plainThumbnailFile(item.mediaId))
        for (item in items) assertFalse(isWiped(item.mediaKey))
    }

    @Test
    fun takeDetachesEveryItemWithoutDeletingFilesKeepingTheGenerationAndLocation() = runTest {
        val tray = tray()
        val items = listOf(storedItem(1), storedItem(2))
        items.forEach(tray::add)
        tray.setDeviceLocation(DeviceLocation(GeoPoint(1.0, 2.0), null))
        val generation = tray.generation
        var notified = 0
        tray.subscribe { notified += 1 }

        val taken = tray.take()

        assertEquals(idsOf(items), idsOf(taken))
        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertEquals(generation, tray.generation)
        assertNotNull(tray.deviceLocation)
        assertEquals(1, notified)
        tray.clear(true)
        assertEquals(4, fileStore.listSizes().size)
        for (item in items) assertFalse(isWiped(item.mediaKey))
    }

    @Test
    fun incrementsTheGenerationOnEveryClearNotOnAddOrRemove() = runTest {
        val tray = tray()
        val first = storedItem(1)
        val start = tray.generation

        tray.add(first)
        tray.remove(first.mediaId)
        assertEquals(start, tray.generation)

        tray.clear(false)
        assertTrue(tray.generation > start)
        val afterFirst = tray.generation

        tray.clear(true)
        assertTrue(tray.generation > afterFirst)
    }

    @Test
    fun addsAnImportThatFinishesInTheCurrentGeneration() = runTest {
        val tray = tray()
        val item = storedItem(1)

        val added = tray.addImported(item, tray.generation)

        assertTrue(added)
        assertEquals(listOf(item.mediaId), idsOf(tray.items))
    }

    @Test
    fun deletesTheFilesAndWipesTheKeyOfAnImportThatFinishesAfterAClear() = runTest {
        val tray = tray()
        val item = storedItem(1)
        val started = tray.generation
        tray.clear(true)

        val added = tray.addImported(item, started)

        assertFalse(added)
        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertNull(fileStore.size(item.originalObjectId))
        assertNull(fileStore.size(item.thumbnailObjectId))
        assertNull(fileStore.plainThumbnailFile(item.mediaId))
        assertTrue(isWiped(item.mediaKey))
    }

    @Test
    fun rejectsAnImportWhileTheAccountIsNotUnlocked() = runTest {
        var unlocked = false
        val tray = tray(isUnlocked = { unlocked })
        val item = storedItem(1)

        val added = tray.addImported(item, tray.generation)

        assertFalse(added)
        assertEquals(emptyList<ImportedMedia>(), tray.items)
        assertNull(fileStore.size(item.originalObjectId))
        assertTrue(isWiped(item.mediaKey))

        unlocked = true
        val second = storedItem(2)
        assertTrue(tray.addImported(second, tray.generation))
    }

    @Test
    fun keepsTheDeviceLocationOfTheCaptureSession() {
        val tray = tray()
        val location = DeviceLocation(GeoPoint(48.8584, 2.2945), 12.0)

        tray.setDeviceLocation(location)
        assertEquals(location, tray.deviceLocation)

        tray.setDeviceLocation(null)
        assertNull(tray.deviceLocation)
    }

    @Test
    fun notifiesSubscribersOnAddRemoveReorderAndClearAndStopsAfterUnsubscribe() = runTest {
        val tray = tray()
        var notifications = 0
        val unsubscribe = tray.subscribe { notifications += 1 }
        val first = storedItem(1)
        val second = storedItem(2)
        val counts = mutableListOf<Int>()

        tray.add(first)
        counts += notifications
        tray.add(second)
        counts += notifications
        tray.reorder(0, 1)
        counts += notifications
        tray.remove(first.mediaId)
        counts += notifications
        tray.clear(true)
        counts += notifications

        assertTrue(counts[0] > 0)
        for (index in 1 until counts.size) assertTrue(counts[index] > counts[index - 1])

        unsubscribe()
        val before = notifications
        tray.add(storedItem(3))
        assertEquals(before, notifications)
    }

    @Test
    fun aNewTrayHoldsNothingFromAnEarlierOne() = runTest {
        val earlier = tray()
        earlier.add(storedItem(1))
        earlier.setDeviceLocation(DeviceLocation(GeoPoint(1.0, 2.0), null))

        val later = tray()

        assertEquals(emptyList<ImportedMedia>(), later.items)
        assertNull(later.deviceLocation)
    }
}
