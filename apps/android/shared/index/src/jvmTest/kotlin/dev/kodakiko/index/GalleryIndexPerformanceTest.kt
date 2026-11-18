package dev.kodakiko.index

import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

class GalleryIndexPerformanceTest {
    private val entryCount = 20_000
    private val wordsPerNote = 30
    private val vocabularySize = 5_000
    private val home = "home-collection"
    private val queryBudget = 500.milliseconds
    private val start = Instant.parse("2020-01-01T00:00:00Z")
    private val stepMinutes = 97L
    private val tagCount = 40
    private val warmups = 3

    private fun entry(position: Int): IndexedEntry {
        val taken = start.plusSeconds(position * stepMinutes * 60)
        val localDate = taken.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        return IndexedEntry(
            id = "e$position",
            isOwn = true,
            collectionIds = listOf(home),
            trashed = false,
            unreadable = false,
            takenAtUtc = taken.toString(),
            utcOffsetMinutes = 0,
            localDate = localDate,
            monthDay = localDate.substring(5),
            tags = listOf("tag${position % tagCount}"),
            coverMediaId = "m$position",
            mediaCount = 1 + position % 3,
            hasVideo = position % 7 == 0,
            notePreview = "",
            pending = false,
        )
    }

    private fun filter(text: String, tags: List<String> = emptyList()) =
        GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, tags, null, null, text)

    @Test
    fun queriesOverTwentyThousandEntriesStayFast() {
        val random = Random(seed = 75)
        val vocabulary = List(vocabularySize) { "word${it.toString(36)}" }
        val index = GalleryIndex(home)
        val build = measureTimedValue {
            for (position in 0 until entryCount) {
                index.upsert(entry(position), List(wordsPerNote) { vocabulary[random.nextInt(vocabularySize)] }.joinToString(" "))
            }
        }.duration
        val filters = listOf(
            "unfiltered" to filter(""),
            "one prefix" to filter("word1"),
            "two words" to filter("wordz word1"),
            "broad prefix" to filter("wo"),
            "tag and text" to filter("word2", listOf("tag3")),
        )
        val cold = "cold unfiltered" to measureTimedValue { index.query(filter("")) }.duration
        repeat(warmups) { filters.forEach { (_, filter) -> index.query(filter) } }
        val timings: List<Pair<String, Duration>> =
            listOf(cold) + filters.map { (name, filter) -> name to measureTimedValue { index.query(filter) }.duration }
        println("GalleryIndex build of $entryCount entries x $wordsPerNote words: $build")
        timings.forEach { (name, duration) -> println("GalleryIndex query '$name': $duration") }
        assertTrue(index.size() == entryCount)
        timings.forEach { (name, duration) -> assertTrue(duration < queryBudget, "$name took $duration") }
    }
}
