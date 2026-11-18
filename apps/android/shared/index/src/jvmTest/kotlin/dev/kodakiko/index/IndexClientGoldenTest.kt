package dev.kodakiko.index

import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class IndexClientGoldenTest {
    private val arrange = GoldenAdapter { fn, args ->
        IndexGoldenAdapter.call(if (fn == "constants") "arrange.constants" else fn, args)
    }

    @Test
    fun galleryArrange() = GoldenReplay.runClient("gallery/arrange.json", arrange)
}
