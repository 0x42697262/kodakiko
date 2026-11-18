package dev.kodakiko.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownPlainTextTest {
    @Test
    fun inlineMarksAreDroppedAndTheirTextKept() {
        assertEquals("Lake day swim and dive, gone", markdownPlainText("Lake day **swim** and _dive_, ~~gone~~"))
    }

    @Test
    fun linksKeepTheirTextAndInlineCodeItsCode() {
        assertEquals("See the map and run ls -la", markdownPlainText("See [the map](https://example.org/map) and run `ls -la`"))
    }

    @Test
    fun headingsAndParagraphsAreSeparatedByABlankLine() {
        assertEquals("Lake\n\nWe swam.\n\nThen ate.", markdownPlainText("# Lake\n\nWe swam.\n\nThen ate."))
    }

    @Test
    fun bulletOrderedAndTaskListsGetPlainMarkers() {
        assertEquals("• towel\n• hat", markdownPlainText("- towel\n- hat"))
        assertEquals("3. first\n4. second", markdownPlainText("3. first\n4. second"))
        assertEquals("☑ towel\n☐ sunscreen", markdownPlainText("- [x] towel\n- [ ] sunscreen"))
    }

    @Test
    fun nestedListLinesAreIndentedUnderTheirItem() {
        assertEquals("• fruit\n  • apple\n  • pear", markdownPlainText("- fruit\n  - apple\n  - pear"))
    }

    @Test
    fun quotesKeepTheirTextCodeBlocksTheirCodeAndRulesDisappear() {
        assertEquals("quoted line\n\nval x = 1\n\nafter", markdownPlainText("> quoted line\n\n```\nval x = 1\n```\n\n---\n\nafter"))
    }

    @Test
    fun emptyAndBlankNotesGiveEmptyText() {
        assertEquals("", markdownPlainText(""))
        assertEquals("", markdownPlainText("   \n\n  "))
        assertEquals("", markdownPlainText("---"))
    }

    @Test
    fun theEditPagesCopyExampleReadsAsPlainText() {
        assertEquals("Lake\n\n• swim\n☑ towel", markdownPlainText("# Lake\n\n- **swim**\n- [x] towel"))
    }
}
