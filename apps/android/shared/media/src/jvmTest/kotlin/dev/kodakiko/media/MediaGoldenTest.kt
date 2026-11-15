package dev.kodakiko.media

import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class MediaGoldenTest {
    @Test
    fun sniff() = GoldenReplay.run("media/sniff.json", MediaGoldenAdapter)

    @Test
    fun exif() = GoldenReplay.run("media/exif.json", MediaGoldenAdapter)

    @Test
    fun isoBmff() = GoldenReplay.run("media/isobmff.json", MediaGoldenAdapter)

    @Test
    fun captureTime() = GoldenReplay.run("media/capture-time.json", MediaGoldenAdapter)

    @Test
    fun geometry() = GoldenReplay.run("media/geometry.json", MediaGoldenAdapter)

    @Test
    fun strip() = GoldenReplay.run("media/strip.json", MediaGoldenAdapter)
}
