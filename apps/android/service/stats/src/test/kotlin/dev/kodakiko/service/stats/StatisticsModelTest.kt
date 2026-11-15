package dev.kodakiko.service.stats

import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.IndexedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

fun indexedEntry(
    id: String,
    takenAtUtc: String,
    mediaCount: Int = 1,
    tags: List<String> = emptyList(),
    notePreview: String = "",
    hasVideo: Boolean = false,
    pending: Boolean = false,
    favorite: Boolean? = null,
    videoCount: Int? = null,
    videoMs: Long? = null,
    originalBytes: Long? = null,
    trashed: Boolean = false,
    collectionIds: List<String> = listOf("home"),
): IndexedEntry {
    val localDate = localDateOf(takenAtUtc, 0)
    return IndexedEntry(
        id = id,
        isOwn = true,
        collectionIds = collectionIds,
        trashed = trashed,
        unreadable = false,
        takenAtUtc = takenAtUtc,
        utcOffsetMinutes = 0,
        localDate = localDate,
        monthDay = localDate.substring(5),
        tags = tags,
        coverMediaId = null,
        mediaCount = mediaCount,
        hasVideo = hasVideo,
        notePreview = notePreview,
        pending = pending,
        favorite = favorite,
        videoCount = videoCount,
        videoMs = videoMs,
        originalBytes = originalBytes,
    )
}

class StatisticsModelTest {
    private val entries = listOf(
        indexedEntry("a", "2025-12-20T10:00:00.000Z", mediaCount = 3, tags = listOf("beach", "family"), notePreview = "x", originalBytes = 3000, favorite = true),
        indexedEntry("b", "2026-10-01T10:00:00.000Z", hasVideo = true, tags = listOf("beach"), videoCount = 1, videoMs = 65_000, originalBytes = 7000),
        indexedEntry("c", "2026-11-05T10:00:00.000Z", tags = listOf("beach"), pending = true),
    )
    private val stats: JournalStatistics = journalStatistics(entries, "2026-11-14")

    @Test
    fun countsEntriesItemsVideosNotesAndWaitingEntries() {
        assertEquals(3, stats.entries)
        assertEquals(5, stats.mediaItems)
        assertEquals(1, stats.entriesWithVideo)
        assertEquals(1, stats.entriesWithNote)
        assertEquals(1, stats.waitingToUpload)
    }

    @Test
    fun splitsPhotosAndVideosSumsVideoLengthAndJournalSizeCountsFavoritesAndTags() {
        assertEquals(4, stats.photos)
        assertEquals(1, stats.videos)
        assertEquals(65_000L, stats.videoMs)
        assertEquals(10_000L, stats.originalBytes)
        assertEquals(1, stats.favorites)
        assertEquals(2, stats.tagCount)
    }

    @Test
    fun ranksTagsByCountAndGivesTheFirstAndLastDay() {
        assertEquals(listOf(TopTag("beach", 3), TopTag("family", 1)), stats.topTags)
        assertEquals("2025-12-20", stats.firstDay)
        assertEquals("2026-11-05", stats.lastDay)
    }

    @Test
    fun givesTheLast12MonthsOldestFirstAndEveryYear() {
        assertEquals(12, stats.perMonth.size)
        assertEquals(MonthCount("2025-12", 1), stats.perMonth[0])
        assertEquals(MonthCount("2026-11", 1), stats.perMonth[11])
        assertEquals(MonthCount("2026-10", 1), stats.perMonth[10])
        assertEquals(listOf(YearCount("2025", 1), YearCount("2026", 2)), stats.perYear)
    }

    @Test
    fun anEmptyJournalHasZeroCountsNoDaysAndTwelveEmptyMonths() {
        val empty = journalStatistics(emptyList(), "2026-11-14")
        assertEquals(
            JournalStatistics(
                entries = 0,
                mediaItems = 0,
                entriesWithVideo = 0,
                entriesWithNote = 0,
                waitingToUpload = 0,
                photos = 0,
                videos = 0,
                videoMs = 0,
                originalBytes = 0,
                favorites = 0,
                tagCount = 0,
                topTags = emptyList(),
                firstDay = null,
                lastDay = null,
                perMonth = listOf(
                    "2025-12", "2026-01", "2026-02", "2026-03", "2026-04", "2026-05",
                    "2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11",
                ).map { MonthCount(it, 0) },
                perYear = emptyList(),
            ),
            empty,
        )
        assertNull(empty.firstDay)
    }

    @Test
    fun aVideoEntryWithoutAVideoCountCountsAsOneVideo() {
        val legacy = journalStatistics(
            listOf(
                indexedEntry("v", "2026-11-01T10:00:00.000Z", mediaCount = 4, hasVideo = true),
                indexedEntry("w", "2026-11-02T10:00:00.000Z", mediaCount = 3, hasVideo = true, videoCount = 2),
                indexedEntry("p", "2026-11-03T10:00:00.000Z", mediaCount = 2, hasVideo = false),
            ),
            "2026-11-14",
        )
        assertEquals(3, legacy.videos)
        assertEquals(6, legacy.photos)
        assertEquals(9, legacy.mediaItems)
        assertEquals(2, legacy.entriesWithVideo)
        assertEquals(0L, legacy.videoMs)
        assertEquals(0L, legacy.originalBytes)
        assertEquals(0, legacy.favorites)
    }

    @Test
    fun favoritesCountOnlyEntriesMarkedTrue() {
        val marked = journalStatistics(
            listOf(
                indexedEntry("a", "2026-11-01T10:00:00.000Z", favorite = true),
                indexedEntry("b", "2026-11-01T11:00:00.000Z", favorite = false),
                indexedEntry("c", "2026-11-01T12:00:00.000Z", favorite = null),
            ),
            "2026-11-14",
        )
        assertEquals(1, marked.favorites)
    }

    @Test
    fun tiedTagsAreOrderedByNameAndOnlyTheTopTenAreKept() {
        val tags = listOf("kilo", "juliet", "india", "hotel", "golf", "foxtrot", "echo", "delta", "charlie", "bravo", "alpha")
        val many = journalStatistics(
            listOf(
                indexedEntry("a", "2026-11-01T10:00:00.000Z", tags = tags),
                indexedEntry("b", "2026-11-02T10:00:00.000Z", tags = listOf("kilo", "zulu")),
                indexedEntry("c", "2026-11-03T10:00:00.000Z", tags = listOf("zulu")),
            ),
            "2026-11-14",
        )
        assertEquals(12, many.tagCount)
        assertEquals(
            listOf(
                TopTag("kilo", 2), TopTag("zulu", 2), TopTag("alpha", 1), TopTag("bravo", 1), TopTag("charlie", 1),
                TopTag("delta", 1), TopTag("echo", 1), TopTag("foxtrot", 1), TopTag("golf", 1), TopTag("hotel", 1),
            ),
            many.topTags,
        )
    }

    @Test
    fun theTwelveMonthWindowCrossesTheYearBoundaryAndIgnoresOlderAndFutureMonths() {
        val window = journalStatistics(
            listOf(
                indexedEntry("old", "2025-01-31T10:00:00.000Z"),
                indexedEntry("first", "2025-02-01T10:00:00.000Z"),
                indexedEntry("jan", "2026-01-15T10:00:00.000Z"),
                indexedEntry("jan2", "2026-01-16T10:00:00.000Z"),
                indexedEntry("future", "2026-02-01T10:00:00.000Z"),
            ),
            "2026-01-15",
        )
        assertEquals(
            listOf(
                MonthCount("2025-02", 1), MonthCount("2025-03", 0), MonthCount("2025-04", 0), MonthCount("2025-05", 0),
                MonthCount("2025-06", 0), MonthCount("2025-07", 0), MonthCount("2025-08", 0), MonthCount("2025-09", 0),
                MonthCount("2025-10", 0), MonthCount("2025-11", 0), MonthCount("2025-12", 0), MonthCount("2026-01", 2),
            ),
            window.perMonth,
        )
        assertEquals(listOf(YearCount("2025", 2), YearCount("2026", 3)), window.perYear)
        assertEquals("2025-01-31", window.firstDay)
        assertEquals("2026-02-01", window.lastDay)
    }

    @Test
    fun yearsAreListedInAscendingOrderWhateverTheEntryOrder() {
        val years = journalStatistics(
            listOf(
                indexedEntry("a", "2024-05-01T10:00:00.000Z"),
                indexedEntry("b", "2022-05-01T10:00:00.000Z"),
                indexedEntry("c", "2023-05-01T10:00:00.000Z"),
                indexedEntry("d", "2022-06-01T10:00:00.000Z"),
            ),
            "2024-12-31",
        )
        assertEquals(listOf(YearCount("2022", 2), YearCount("2023", 1), YearCount("2024", 1)), years.perYear)
        assertEquals("2022-05-01", years.firstDay)
        assertEquals("2024-05-01", years.lastDay)
    }
}
