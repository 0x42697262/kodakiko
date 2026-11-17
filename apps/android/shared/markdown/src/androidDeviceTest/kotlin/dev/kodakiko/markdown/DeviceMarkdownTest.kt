package dev.kodakiko.markdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceMarkdownTest {
    private val document = listOf(
        "# Lake day",
        "## Morning {swim}",
        "Some **bold**, *italic*, ~~gone~~ and `code` with a [link](https://example.com) and <https://kodakiko.dev>.",
        "",
        "> quoted line",
        "",
        "- [ ] towels",
        "- [x] sunscreen",
        "1. first",
        "2. second",
        "",
        "```kotlin",
        "val x = mapOf(1 to 2)",
        "```",
        "",
        "---",
        "",
        "| a | b |",
        "|---|---|",
        "| 1 | 2 |",
        "",
        "Setext title",
        "============",
        "",
        "Line with a hard break  ",
        "and an escaped \\* star and a {brace} pair.",
    ).joinToString("\n")

    @Test
    fun theWholeParserCompilesAndParsesOnTheDeviceRegexEngine() {
        val blocks = parseMarkdown(document)
        assertTrue(blocks.size > 1)
    }

    @Test
    fun everyToolbarActionRunsOnTheDeviceRegexEngine() {
        val text = "Lake day swim"
        val selection = TextSelection(9, 13)
        val edited = MarkdownAction.entries.map { applyMarkdownAction(it, text, selection, "09:00") }
        assertEquals(MarkdownAction.entries.size, edited.size)
    }
}
