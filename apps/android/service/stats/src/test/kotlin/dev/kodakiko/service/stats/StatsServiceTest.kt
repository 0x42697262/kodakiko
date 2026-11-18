@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.stats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.js.JsDate
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntriesClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private class FixedClock(var nowMs: Long, var timezoneOffset: Long) : EntriesClock {
    override fun now(): Long = nowMs

    override fun nowIso(): String = JsDate.toIsoString(nowMs.toDouble())

    override fun timezoneOffsetMinutes(epochMs: Long): Long = timezoneOffset

    override fun timeZone(): String? = null
}

@RunWith(RobolectricTestRunner::class)
class StatsServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sodium: Sodium = createSodium()
    private lateinit var rowStore: DatabaseRowStore
    private val clock = FixedClock(JsDate.parse("2026-11-14T12:00:00.000Z").toLong(), 0)
    private var index: GalleryIndex? = null
    private var technical = false
    private val metadata = HashMap<String, EntryMetadataV1>()
    private val reads = CopyOnWriteArrayList<String>()

    @Before
    fun open() {
        rowStore = DatabaseRowStore(context, sodium)
        rowStore.open("stats/account-" + encodeBytes(sodium.randomBytes(16)))
    }

    @After
    fun close() = rowStore.close()

    private fun TestScope.service(io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)): StatsService =
        DefaultStatsService(
            StatsServiceDeps(
                index = { index },
                clock = clock,
                readMetadata = { entryId: String ->
                    reads += entryId
                    metadata[entryId] ?: error("no metadata for $entryId")
                },
                rowStore = rowStore,
                technical = { technical },
                ioDispatcher = io,
            ),
        )

    private fun indexOf(vararg entries: IndexedEntry): GalleryIndex =
        GalleryIndex("home").apply { entries.forEach { upsert(it, it.notePreview) } }

    @Test
    fun statisticsCoverTheHomeJournalWithoutTrashedOrOtherCollectionEntries() = runTest {
        index = indexOf(
            indexedEntry("a", "2025-12-20T10:00:00.000Z", mediaCount = 3, tags = listOf("beach", "family"), notePreview = "x", originalBytes = 3000, favorite = true),
            indexedEntry("b", "2026-10-01T10:00:00.000Z", hasVideo = true, tags = listOf("beach"), videoCount = 1, videoMs = 65_000, originalBytes = 7000),
            indexedEntry("c", "2026-11-05T10:00:00.000Z", tags = listOf("beach"), pending = true),
            indexedEntry("t", "2026-11-06T10:00:00.000Z", tags = listOf("trash"), trashed = true),
            indexedEntry("o", "2026-11-07T10:00:00.000Z", tags = listOf("other"), collectionIds = listOf("journal-2")),
        )

        val stats: JournalStatistics = service().statistics()

        assertEquals(3, stats.entries)
        assertEquals(5, stats.mediaItems)
        assertEquals(listOf(TopTag("beach", 3), TopTag("family", 1)), stats.topTags)
        assertEquals(MonthCount("2026-11", 1), stats.perMonth.last())
        assertEquals("2026-11-05", stats.lastDay)
        assertEquals(journalStatistics(listOf("a", "b", "c").map { index!!.get(it)!! }, "2026-11-14"), stats)
    }

    @Test
    fun statisticsWithoutAnIndexAreEmpty() = runTest {
        index = null
        val stats: JournalStatistics = service().statistics()
        assertEquals(journalStatistics(emptyList(), "2026-11-14"), stats)
    }

    @Test
    fun todayIsTheDeviceLocalDateSoTheMonthWindowFollowsTheTimeZone() = runTest {
        index = indexOf(indexedEntry("d", "2026-12-01T05:00:00.000Z"))
        clock.nowMs = JsDate.parse("2026-11-30T23:30:00.000Z").toLong()
        clock.timezoneOffset = -60

        val ahead: JournalStatistics = service().statistics()
        assertEquals(MonthCount("2026-12", 1), ahead.perMonth.last())
        assertEquals(MonthCount("2026-01", 0), ahead.perMonth.first())

        clock.timezoneOffset = 60
        val behind: JournalStatistics = service().statistics()
        assertEquals(MonthCount("2026-11", 0), behind.perMonth.last())
    }

    @Test
    fun entryDetailsReadTheMetadataTheEntryRowAndTheObjectRows() = runTest {
        metadata["ENTRY1"] = DETAILS_METADATA
        rowStore.transaction { tx ->
            tx.entries.put(detailsEntryRow())
            tx.objects.put(detailsObjectRow("OBJ1", "MEDIA1", ObjectVariant.ORIGINAL, 5_300_000, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED))
        }
        technical = true

        val sections: List<DetailSection> = service().entryDetails("ENTRY1")

        assertEquals(listOf("ENTRY1"), reads.toList())
        assertEquals(
            entryDetailSections(
                entryId = "ENTRY1",
                metadata = DETAILS_METADATA,
                row = detailsEntryRow(),
                objects = mapOf("MEDIA1" to listOf(detailsObjectRow("OBJ1", "MEDIA1", ObjectVariant.ORIGINAL, 5_300_000, ObjectLocalState.PRESENT, ObjectUploadState.UPLOADED))),
                technical = true,
            ).map { section -> section.kind to section.rows },
            sections.map { section -> section.kind to section.rows },
        )
        assertTrue(sections[0].rows.any { it.label == DetailLabel.ID })
    }

    @Test
    fun entryDetailsFollowTheTechnicalDetailsSettingAndAMissingRow() = runTest {
        metadata["ENTRY1"] = DETAILS_METADATA
        technical = false

        val sections: List<DetailSection> = service().entryDetails("ENTRY1")

        val labels = sections[0].rows.map { it.label }
        assertEquals(listOf(DetailLabel.TAKEN, DetailLabel.LOCATION, DetailLabel.TAGS, DetailLabel.NOTE_LENGTH), labels)
        assertEquals(DetailRow(DetailLabel.ORIGINAL, DetailValue.Unknown), sections[1].rows.last())
    }

    @Test
    fun entryDetailsPassAMetadataFailureThrough() = runTest {
        val failure = try {
            service().entryDetails("UNKNOWN")
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            error
        }
        assertEquals("no metadata for UNKNOWN", failure?.message)
    }

    @Test
    fun entryDetailsReadTheDatabaseOnTheInjectedIoDispatcher() = runTest {
        metadata["ENTRY1"] = DETAILS_METADATA
        val threads = CopyOnWriteArrayList<String>()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "stats-io-test") }
        val io: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
        val probing = object : RowStorePort by rowStore {
            override fun <T> transaction(work: (RowStoreTx) -> T): T {
                threads += Thread.currentThread().name
                return rowStore.transaction(work)
            }
        }
        try {
            DefaultStatsService(
                StatsServiceDeps(
                    index = { null },
                    clock = clock,
                    readMetadata = { entryId: String -> metadata.getValue(entryId) },
                    rowStore = probing,
                    technical = { false },
                    ioDispatcher = io,
                ),
            ).entryDetails("ENTRY1")
        } finally {
            io.close()
        }
        assertTrue(threads.isNotEmpty())
        assertTrue(threads.toList().toString(), threads.all { it == "stats-io-test" })
    }
}
