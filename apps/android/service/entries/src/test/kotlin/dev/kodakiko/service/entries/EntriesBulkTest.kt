package dev.kodakiko.service.entries

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntriesBulkTest : EntriesTestBase() {
    @Test
    fun marksAndUnmarksFavoritesInBulkAndSkipsEntriesAlreadyInThatState() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        for (id in listOf(first, second)) harness.markSynced(id)
        assertEquals(BulkResult(changed = 1, skipped = 0), harness.service.setFavorite(listOf(first), true))
        assertEquals(BulkResult(changed = 1, skipped = 1), harness.service.setFavorite(listOf(first, second), true))
        assertEquals(true, harness.metadataOf(second).favorite)
        assertEquals(BulkResult(changed = 1, skipped = 0), harness.service.setFavorite(listOf(first), false))
        assertNull(harness.metadataOf(first).favorite)
    }

    @Test
    fun renamesAndDeletesATagOnEveryEntryThatHasIt() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("trip", "a"))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("b"))
        for (id in listOf(first, second)) harness.markSynced(id)
        assertEquals(BulkResult(changed = 1, skipped = 1), harness.service.renameTag(listOf(first, second), "trip", "Journey"))
        assertEquals(listOf("a", "journey"), harness.metadataOf(first).tags)
        assertEquals(BulkResult(changed = 1, skipped = 1), harness.service.deleteTag(listOf(first, second), "a"))
        assertEquals(listOf("journey"), harness.metadataOf(first).tags)
    }

    @Test
    fun b023AddsATagToThreeEntriesWhereOneAlreadyHasItChangedTwoSkippedOne() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("a"))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("trip", "a"))
        val third = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        for (id in listOf(first, second, third)) harness.markSynced(id)

        val result = harness.service.addTagToMany(listOf(first, second, third), " Trip ")

        assertEquals(BulkResult(changed = 2, skipped = 1), result)
        for (id in listOf(first, second, third)) assertTrue("trip" in harness.metadataOf(id).tags)
        assertEquals(listOf("a", "trip"), harness.metadataOf(first).tags)
        val queued = harness.outbox()
        assertEquals(listOf("updateEntryMeta", "updateEntryMeta"), queued.map { it.kind })
        assertEquals(listOf(entryPayload(first), entryPayload(third)), queued.map { it.payload })
    }

    @Test
    fun addTagToManySkipsEntriesThatAreNotOwned() = runTest {
        val harness = harness()
        val owned = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val foreign = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        harness.setOwner(foreign, OTHER_USER)
        assertEquals(BulkResult(changed = 1, skipped = 1), harness.service.addTagToMany(listOf(owned, foreign), "x"))
    }

    @Test
    fun removesATagFromTheEntriesThatHaveItAndSkipsTheOthers() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("a", "b"))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("b"))
        val third = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("c"))
        for (id in listOf(first, second, third)) harness.markSynced(id)

        val result = harness.service.removeTagFromMany(listOf(first, second, third), "B")

        assertEquals(BulkResult(changed = 2, skipped = 1), result)
        assertEquals(listOf("a"), harness.metadataOf(first).tags)
        assertEquals(emptyList<String>(), harness.metadataOf(second).tags)
        assertEquals(listOf("c"), harness.metadataOf(third).tags)
    }

    @Test
    fun skipsEveryEntryForABlankTag() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        assertEquals(BulkResult(changed = 0, skipped = 1), harness.service.addTagToMany(listOf(first), "   "))
        assertEquals(BulkResult(changed = 0, skipped = 1), harness.service.removeTagFromMany(listOf(first), "   "))
    }

    @Test
    fun skipsAnEntryWhoseTagListIsFull() = runTest {
        val harness = harness()
        val full = List(50) { "t" + it.toString().padStart(2, '0') }
        val entryId = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = full)
        assertEquals(BulkResult(changed = 0, skipped = 1), harness.service.addTagToMany(listOf(entryId), "extra"))
    }

    @Test
    fun notifiesOnceWithEveryChangedEntryOfABulkEdit() = runTest {
        val harness = harness()
        val first = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        val second = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)), tags = listOf("x"))
        val third = harness.createEntry(listOf(fakeJpeg(PHOTO_BYTES)))
        for (id in listOf(first, second, third)) harness.markSynced(id)
        harness.changed.clear()
        val notifiedBefore = harness.notified.get()

        assertEquals(BulkResult(changed = 2, skipped = 1), harness.service.addTagToMany(listOf(first, second, third, first), "x"))

        assertEquals(listOf(listOf(first, third)), harness.changed)
        assertEquals(notifiedBefore + 1, harness.notified.get())
        assertEquals(BulkResult(changed = 0, skipped = 3), harness.service.addTagToMany(listOf(first, second, third), "x"))
        assertEquals(1, harness.changed.size)
    }
}
