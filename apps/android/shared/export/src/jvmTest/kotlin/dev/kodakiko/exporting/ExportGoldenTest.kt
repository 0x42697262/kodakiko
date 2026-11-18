package dev.kodakiko.exporting

import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class ExportGoldenTest {
    private fun scoped(area: String) = GoldenAdapter { fn, args ->
        ExportGoldenAdapter.call(if (fn == "constants") "$area.constants" else fn, args)
    }

    @Test
    fun layout() = GoldenReplay.run("export/layout.json", scoped("layout"))

    @Test
    fun parseEntry() = GoldenReplay.run("export/parse-entry.json", scoped("parse"))

    @Test
    fun kdkx() = GoldenReplay.run("export/kdkx.json", scoped("kdkx"))

    @Test
    fun zip() = GoldenReplay.run("export/zip.json", scoped("zip"))
}
