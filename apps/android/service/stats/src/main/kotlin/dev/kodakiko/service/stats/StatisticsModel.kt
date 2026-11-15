package dev.kodakiko.service.stats

import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.js.jsLocaleCompare
import dev.kodakiko.js.jsStableSort

data class TopTag(val tag: String, val count: Int)

data class MonthCount(val month: String, val count: Int)

data class YearCount(val year: String, val count: Int)

data class JournalStatistics(
    val entries: Int,
    val mediaItems: Int,
    val entriesWithVideo: Int,
    val entriesWithNote: Int,
    val waitingToUpload: Int,
    val photos: Int,
    val videos: Int,
    val videoMs: Long,
    val originalBytes: Long,
    val favorites: Int,
    val tagCount: Int,
    val topTags: List<TopTag>,
    val firstDay: String?,
    val lastDay: String?,
    val perMonth: List<MonthCount>,
    val perYear: List<YearCount>,
)

private fun monthsEndingAt(today: String): List<String> {
    var year = today.substring(0, StatsConstants.YEAR_CHARS).toInt()
    var month = today.substring(StatsConstants.MONTH_START, StatsConstants.MONTH_CHARS).toInt()
    val months = ArrayDeque<String>()
    repeat(StatsConstants.MONTHS_SHOWN) {
        months.addFirst(year.toString() + StatsConstants.MONTH_SEPARATOR + month.toString().padStart(StatsConstants.PAD, StatsConstants.PAD_CHAR))
        month -= 1
        if (month == 0) {
            month = StatsConstants.MONTHS_PER_YEAR
            year -= 1
        }
    }
    return months.toList()
}

private fun videosOf(entry: IndexedEntry): Int = entry.videoCount ?: if (entry.hasVideo) 1 else 0

private fun MutableMap<String, Int>.increment(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

fun journalStatistics(entries: List<IndexedEntry>, todayLocalDate: String): JournalStatistics {
    val tags = LinkedHashMap<String, Int>()
    val months = LinkedHashMap<String, Int>()
    val years = LinkedHashMap<String, Int>()
    var first: String? = null
    var last: String? = null
    for (entry in entries) {
        entry.tags.forEach { tags.increment(it) }
        months.increment(entry.localDate.take(StatsConstants.MONTH_CHARS))
        years.increment(entry.localDate.take(StatsConstants.YEAR_CHARS))
        if (first == null || entry.localDate < first) first = entry.localDate
        if (last == null || entry.localDate > last) last = entry.localDate
    }
    val rankedTags = jsStableSort(tags.entries.map { it.key to it.value }) { (leftTag, left), (rightTag, right) ->
        val byCount = right - left
        if (byCount != 0) byCount else jsLocaleCompare(leftTag, rightTag)
    }
    val sortedYears = jsStableSort(years.entries.map { it.key to it.value }) { (left), (right) -> jsLocaleCompare(left, right) }
    return JournalStatistics(
        entries = entries.size,
        mediaItems = entries.sumOf { it.mediaCount },
        entriesWithVideo = entries.count { it.hasVideo },
        entriesWithNote = entries.count { it.notePreview != StatsConstants.NO_TEXT },
        waitingToUpload = entries.count { it.pending },
        photos = entries.sumOf { it.mediaCount - videosOf(it) },
        videos = entries.sumOf { videosOf(it) },
        videoMs = entries.sumOf { it.videoMs ?: 0L },
        originalBytes = entries.sumOf { it.originalBytes ?: 0L },
        favorites = entries.count { it.favorite == true },
        tagCount = tags.size,
        topTags = rankedTags.take(StatsConstants.TOP_TAGS).map { (tag, count) -> TopTag(tag, count) },
        firstDay = first,
        lastDay = last,
        perMonth = monthsEndingAt(todayLocalDate).map { MonthCount(it, months[it] ?: 0) },
        perYear = sortedYears.map { (year, count) -> YearCount(year, count) },
    )
}
