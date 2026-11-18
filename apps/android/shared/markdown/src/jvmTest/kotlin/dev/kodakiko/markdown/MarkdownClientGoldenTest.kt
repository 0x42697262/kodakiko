package dev.kodakiko.markdown

import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class MarkdownClientGoldenTest {
    private fun scoped(area: String) = GoldenAdapter { fn, args ->
        MarkdownGoldenAdapter.call(if (fn == "constants") "$area.constants" else fn, args)
    }

    @Test
    fun parse() = GoldenReplay.runClient("markdown/parse.json", scoped("parse"))

    @Test
    fun toolbar() = GoldenReplay.runClient("markdown/toolbar.json", scoped("toolbar"))
}
