package dev.kodakiko.exporting

import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class ExportGoldenTest {
    @Test
    fun layout() = GoldenReplay.run("export/layout.json", ExportGoldenAdapter)

    @Test
    fun parseEntry() = GoldenReplay.run("export/parse-entry.json", ExportGoldenAdapter)

    @Test
    fun kdkx() = GoldenReplay.run("export/kdkx.json", ExportGoldenAdapter)

    @Test
    fun zip() = GoldenReplay.run("export/zip.json", ExportGoldenAdapter)
}
