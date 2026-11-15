package dev.kodakiko.index

import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class IndexGoldenTest {
    @Test
    fun tokenize() = GoldenReplay.run("index/tokenize.json", IndexGoldenAdapter)

    @Test
    fun galleryQuery() = GoldenReplay.run("index/gallery-query.json", IndexGoldenAdapter)

    @Test
    fun onThisDay() = GoldenReplay.run("index/on-this-day.json", IndexGoldenAdapter)
}
