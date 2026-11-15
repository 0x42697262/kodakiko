@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.index

import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.index.IndexedEntry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IndexServiceTest : IndexTestBase() {
    private fun query(text: String = "", tags: List<String> = emptyList()): List<String> =
        service.index()?.query(JOURNAL.copy(text = text, tags = tags))?.flatMap { it.entryIds } ?: emptyList()

    @Test
    fun buildsOverThreeEntriesAndReportsOneTamperedBlobAsUnreadable() = runTest {
        setUpIndex()
        tamper(entries[2].entryId)
        assertEquals(IndexStatus.IDLE, service.status())
        assertNull(service.index())

        val building = async(start = CoroutineStart.UNDISPATCHED) { service.build() }
        assertEquals(IndexStatus.BUILDING, service.status())
        val report = building.await()

        assertEquals(3, report.entries)
        assertEquals(1, report.unreadable)
        assertTrue(report.durationMs >= 0)
        assertEquals(listOf(report), reports)
        assertEquals(IndexStatus.READY, service.status())
        val index = service.index()
        assertNotNull(index)
        assertEquals(3, index?.size())
        val unreadable = index?.get(entries[2].entryId)
        assertEquals(true, unreadable?.unreadable)
        assertEquals(emptyList<String>(), unreadable?.tags)
        assertEquals(
            IndexedEntry(
                id = entries[2].entryId,
                isOwn = true,
                collectionIds = listOf(fixture.homeCollectionId),
                trashed = false,
                unreadable = true,
                takenAtUtc = NOW_ISO,
                utcOffsetMinutes = 0,
                localDate = "2026-11-12",
                monthDay = "11-12",
                tags = emptyList(),
                coverMediaId = null,
                mediaCount = 0,
                hasVideo = false,
                notePreview = "",
                pending = true,
            ),
            unreadable,
        )
        assertEquals(
            IndexedEntry(
                id = entries[1].entryId,
                isOwn = true,
                collectionIds = listOf(fixture.homeCollectionId),
                trashed = false,
                unreadable = false,
                takenAtUtc = NOW_ISO,
                utcOffsetMinutes = 0,
                localDate = "2026-11-12",
                monthDay = "11-12",
                tags = listOf("beach", "lake"),
                coverMediaId = entries[1].mediaIds[0],
                mediaCount = 1,
                hasVideo = false,
                notePreview = "note number 1",
                pending = true,
                favorite = false,
                videoCount = 0,
                videoMs = 0,
                originalBytes = 32,
            ),
            index?.get(entries[1].entryId),
        )
        assertEquals(3, journalIds().size)
        assertEquals(2, query(tags = listOf("beach")).size)
    }

    @Test
    fun marksAnEntryNotPendingOnceItIsSyncedAndEveryObjectIsUploaded() = runTest {
        setUpIndex()
        val target = entries[0].entryId
        fixture.rowStore.transaction { tx ->
            val row = tx.entries.get(target)
            if (row != null) tx.entries.put(row.copy(localState = EntryLocalState.SYNCED))
            for (mediaId in entries[0].mediaIds) {
                for (row in tx.objects.byMedia(mediaId)) tx.objects.put(row.copy(uploadState = ObjectUploadState.UPLOADED))
            }
        }

        service.build()

        assertEquals(false, service.index()?.get(target)?.pending)
        assertEquals(true, service.index()?.get(entries[1].entryId)?.pending)
    }

    @Test
    fun notifiesSubscribersOnStatusChanges() = runTest {
        setUpIndex()
        val statuses = mutableListOf<IndexStatus>()
        val unsubscribe = service.subscribe { statuses.add(service.status()) }

        service.build()
        unsubscribe()

        assertTrue(statuses.contains(IndexStatus.BUILDING))
        assertEquals(IndexStatus.READY, statuses.last())
    }

    @Test
    fun updatesTheTagsOfAnEditedEntryWithinTheDebounce() = runTest {
        setUpIndex()
        service.build()
        val target = entries[0]
        reseal(target, target.metadata.copy(tags = listOf("forest"), note = "walk in the forest"))
        val before = service.index()?.version() ?: 0

        val emittedAt = testScheduler.currentTime
        events.emit(listOf(target.entryId))
        assertEquals(listOf("beach"), service.index()?.get(target.entryId)?.tags)

        waitFor { assertEquals(listOf("forest"), service.index()?.get(target.entryId)?.tags) }
        assertTrue(testScheduler.currentTime - emittedAt >= IndexServiceConstants.INDEX_UPDATE_DEBOUNCE_MS - WAIT_INTERVAL_MS)
        assertTrue((service.index()?.version() ?: 0) > before)
        assertEquals(listOf(target.entryId), query(text = "fore"))
        assertEquals(listOf(entries[1].entryId), query(tags = listOf("beach")))
    }

    @Test
    fun removesAnEntryWhoseRowIsGone() = runTest {
        setUpIndex()
        service.build()
        val target = entries[1].entryId
        fixture.rowStore.transaction { tx -> tx.entries.delete(target) }

        events.emit(listOf(target))

        waitFor { assertNull(service.index()?.get(target)) }
        assertEquals(2, service.index()?.size())
    }

    @Test
    fun addsANewlyCreatedEntry() = runTest {
        setUpIndex()
        service.build()
        val created = createLocalEntry(fixture, listOf(bytesOf(16, 9)), "new one", listOf("fresh"))

        events.emit(listOf(created.entryId))

        waitFor { assertEquals(listOf("fresh"), service.index()?.get(created.entryId)?.tags) }
    }

    @Test
    fun appliesChangesEmittedWhileBuildingOnceTheBuildFinishes() = runTest {
        setUpIndex()
        val building = async(start = CoroutineStart.UNDISPATCHED) { service.build() }
        val created = createLocalEntry(fixture, listOf(bytesOf(16, 4)), "during build", emptyList())
        events.emit(listOf(created.entryId))
        building.await()

        waitFor { assertNotNull(service.index()?.get(created.entryId)) }
    }

    @Test
    fun appliesAChangeEmittedAfterTheBuildReadItsRowsOnceTheBuildFinishes() = runTest {
        setUpIndex()
        var created: LocalEntry? = null
        entryKeys.beforeKey = {
            if (created == null) {
                val entry = createLocalEntry(fixture, listOf(bytesOf(16, 5)), "after the read", listOf("late"))
                created = entry
                events.emit(listOf(entry.entryId))
            }
        }

        service.build()
        val late = created!!.entryId
        assertEquals(IndexStatus.READY, service.status())
        assertNull(service.index()?.get(late))

        waitFor { assertEquals(listOf("late"), service.index()?.get(late)?.tags) }
    }

    @Test
    fun clearEmptiesTheIndexAndIgnoresLaterChangesUntilTheNextBuild() = runTest {
        setUpIndex()
        service.build()
        service.clear()
        assertEquals(IndexStatus.IDLE, service.status())
        assertNull(service.index())

        events.emit(listOf(entries[0].entryId))
        advanceTimeBy(IndexServiceConstants.INDEX_UPDATE_DEBOUNCE_MS * 2)
        runCurrent()
        assertNull(service.index())

        service.build()
        assertEquals(3, service.index()?.size())
    }

    @Test
    fun aBuildInterruptedByClearIsNotInstalled() = runTest {
        setUpIndex()
        val building = async(start = CoroutineStart.UNDISPATCHED) { runCatching { service.build() } }
        service.clear()
        locked = true
        building.await()

        assertEquals(IndexStatus.IDLE, service.status())
        assertNull(service.index())
        assertEquals(emptyList<IndexBuildReport>(), reports)
    }

    @Test
    fun rebuildsOnceTheHomeCollectionBecomesKnown() = runTest {
        setUpIndex()
        fixture.rowStore.kvDelete(StorageKeys.ACCOUNT_HOME_COLLECTION_ID)
        val collection = fixture.rowStore.transaction { tx -> tx.collections.get(fixture.homeCollectionId) }
        fixture.rowStore.transaction { tx -> tx.collections.delete(fixture.homeCollectionId) }
        service.build()
        assertEquals(emptyList<String>(), journalIds())

        fixture.rowStore.transaction { tx -> if (collection != null) tx.collections.put(collection) }
        fixture.rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(fixture.homeCollectionId))
        events.emit(listOf(entries[0].entryId))

        waitFor { assertEquals(3, journalIds().size) }
    }

    @Test
    fun suggestsOwnTagsRankedByUseThroughTheIndex() = runTest {
        setUpIndex()
        val source = IndexTagSuggestions(service)
        assertEquals(emptyList<String>(), source.suggest(""))
        service.build()
        assertEquals(listOf("beach", "city", "lake"), source.suggest(""))
        assertEquals(listOf("lake"), source.suggest("l"))
        assertEquals(8, IndexServiceConstants.TAG_SUGGESTIONS_MAX)
    }

    @Test
    fun coalescesABurstOfChangesIntoOneUpdateHundredMillisecondsAfterTheLast() = runTest {
        setUpIndex()
        service.build()
        reseal(entries[0], entries[0].metadata.copy(tags = listOf("forest")))
        reseal(entries[1], entries[1].metadata.copy(tags = listOf("river")))
        var notified = 0
        service.subscribe { notified += 1 }
        assertEquals(100L, IndexServiceConstants.INDEX_UPDATE_DEBOUNCE_MS)

        events.emit(listOf(entries[0].entryId))
        advanceTimeBy(60)
        runCurrent()
        events.emit(listOf(entries[1].entryId, entries[0].entryId))
        advanceTimeBy(99)
        runCurrent()
        assertEquals(listOf("beach"), service.index()?.get(entries[0].entryId)?.tags)
        assertEquals(listOf("beach", "lake"), service.index()?.get(entries[1].entryId)?.tags)
        assertEquals(0, notified)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("forest"), service.index()?.get(entries[0].entryId)?.tags)
        assertEquals(listOf("river"), service.index()?.get(entries[1].entryId)?.tags)
        assertEquals(1, notified)
    }

    @Test
    fun theVersionFlowFollowsStatusBuildGenerationAndIndexVersion() = runTest {
        setUpIndex()
        assertEquals(IndexVersion(IndexStatus.IDLE, 0, 0), service.version.value)

        val building = async(start = CoroutineStart.UNDISPATCHED) { service.build() }
        assertEquals(IndexVersion(IndexStatus.BUILDING, 1, 0), service.version.value)
        building.await()
        val built = service.version.value
        assertEquals(IndexVersion(IndexStatus.READY, 1, service.index()?.version() ?: -1), built)

        reseal(entries[0], entries[0].metadata.copy(tags = listOf("forest")))
        events.emit(listOf(entries[0].entryId))
        waitFor { assertTrue(service.version.value.version > built.version) }
        assertEquals(IndexVersion(IndexStatus.READY, 1, service.index()?.version() ?: -1), service.version.value)

        service.clear()
        assertEquals(IndexVersion(IndexStatus.IDLE, 2, 0), service.version.value)
        service.build()
        assertEquals(IndexVersion(IndexStatus.READY, 3, service.index()?.version() ?: -1), service.version.value)
    }

    @Test
    fun aFailedBuildReturnsToIdleAndRethrows() = runTest {
        setUpIndex()
        locked = true

        val failure = runCatching { service.build() }.exceptionOrNull()

        assertTrue(failure is AccountLockedException)
        assertEquals(IndexStatus.IDLE, service.status())
        assertNull(service.index())
        assertEquals(emptyList<IndexBuildReport>(), reports)
    }
}
