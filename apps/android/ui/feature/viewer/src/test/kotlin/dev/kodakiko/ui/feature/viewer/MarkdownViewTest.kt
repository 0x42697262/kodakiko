package dev.kodakiko.ui.feature.viewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1280dp-mdpi")
class MarkdownViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()

    private fun render(source: String) {
        compose.show { MarkdownView(source = source, onOpenLink = { opened += it }) }
    }

    private fun shows(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun blocks(): Int = generateSequence(0) { it + 1 }.takeWhile { compose.exists(listItem(BLOCK, it)) }.count()

    @Test
    fun anEmptyNoteRendersNothing() {
        render("")
        assertFalse(compose.exists(TestTags.Markdown.VIEW))
    }

    @Test
    fun aBlankNoteRendersNothing() {
        render("   \n\n  ")
        assertFalse(compose.exists(TestTags.Markdown.VIEW))
    }

    @Test
    fun everyTopLevelBlockIsNumbered() {
        render("# Title\n\nA paragraph.\n\n- one\n- two\n\n---\n\n> quoted")
        assertTrue(compose.exists(TestTags.Markdown.VIEW))
        assertEquals(5, blocks())
    }

    @Test
    fun headingsAreHeadingsAndDeeperOnesShowAsLevelThree() {
        render("# Title\n\n## Part\n\n#### Deep")
        assertTrue(MarkdownTesting.headingWith(compose, "Title"))
        assertTrue(MarkdownTesting.headingWith(compose, "Part"))
        assertTrue(MarkdownTesting.headingWith(compose, "Deep"))
        assertFalse(shows("#"))
    }

    @Test
    fun aParagraphIsNotAHeading() {
        render("just text")
        assertTrue(shows("just text"))
        assertFalse(MarkdownTesting.headingWith(compose, "just text"))
    }

    @Test
    fun emphasisStrikethroughAndInlineCodeAreStyled() {
        render("**bold** and *slanted* and ~~gone~~ and `mono`")
        assertTrue(shows("bold and slanted and gone and mono"))
        assertTrue(MarkdownTesting.stylesOver(compose, "bold").any { it.fontWeight == FontWeight.Bold })
        assertTrue(MarkdownTesting.stylesOver(compose, "slanted").any { it.fontStyle == FontStyle.Italic })
        assertTrue(MarkdownTesting.stylesOver(compose, "gone").any { it.textDecoration?.contains(TextDecoration.LineThrough) == true })
        assertTrue(MarkdownTesting.stylesOver(compose, "mono").any { it.fontFamily == FontFamily.Monospace })
        assertFalse(MarkdownTesting.stylesOver(compose, "and").any { it.fontWeight == FontWeight.Bold })
        assertFalse(shows("**"))
    }

    @Test
    fun lineBreaksAreKept() {
        render("first line\nsecond line")
        assertTrue(shows("first line\nsecond line"))
    }

    @Test
    fun unorderedListsShowBullets() {
        render("- towel\n- sunscreen")
        assertEquals(2, compose.onAllNodes(hasText("•"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(shows("towel"))
        assertTrue(shows("sunscreen"))
    }

    @Test
    fun orderedListsKeepTheirStartNumber() {
        render("3. three\n4. four")
        assertTrue(shows("3."))
        assertTrue(shows("4."))
        assertFalse(shows("1."))
    }

    @Test
    fun taskListsShowCheckedAndOpenBoxes() {
        render("- [x] packed\n- [ ] booked")
        assertTrue(shows("☑"))
        assertTrue(shows("☐"))
        assertTrue(shows("packed"))
        assertFalse(shows("[x]"))
    }

    @Test
    fun nestedListsAreRendered() {
        render("- outer\n  - inner")
        assertTrue(shows("outer"))
        assertTrue(shows("inner"))
        assertEquals(2, compose.onAllNodes(hasText("•"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun quotesRulesAndCodeBlocksAreRendered() {
        render("> quoted words\n\n---\n\n```\nval x = 1\n```")
        assertTrue(shows("quoted words"))
        val code = MarkdownTesting.textNodes(compose, "val x = 1").first()
        val layout = MarkdownTesting.layoutOf(code)
        assertTrue(layout.layoutInput.style.fontFamily == FontFamily.Monospace || layout.layoutInput.text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertFalse(shows("```"))
    }

    @Test
    fun imagesNeverLoadAndShowTheirAltText() {
        render("![a heron](https://example.com/heron.jpg)\n\n![](https://example.com/x.png)")
        assertTrue(shows("a heron"))
        assertTrue(shows("[image]"))
        assertFalse(shows("example.com"))
    }

    @Test
    fun rawHtmlAndTablesShowAsLiteralText() {
        render("<b>hi</b>\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertTrue(shows("<b>hi</b>"))
        assertTrue(shows("| a | b |"))
    }

    @Test
    fun aLinkAsksBeforeOpeningAndShowsTheFullAddress() {
        render("Visit [our site](https://example.com/path?q=1) today")
        assertFalse(compose.exists(TestTags.Markdown.LINK_SHEET))
        MarkdownTesting.clickText(compose, "our site")
        assertTrue(compose.exists(TestTags.Markdown.LINK_SHEET))
        assertTrue(shows("Open this link?"))
        compose.onNodeWithTag(TestTags.Markdown.LINK_URL, useUnmergedTree = true).assertTextEquals("https://example.com/path?q=1")
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithTag(TestTags.Markdown.OPEN_LINK).assertTextEquals("Open").performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://example.com/path?q=1"), opened)
        assertFalse(compose.exists(TestTags.Markdown.LINK_SHEET))
    }

    @Test
    fun cancellingTheLinkSheetOpensNothing() {
        render("[mail me](mailto:me@example.com)")
        MarkdownTesting.clickText(compose, "mail me")
        compose.onNodeWithTag(TestTags.Markdown.LINK_URL, useUnmergedTree = true).assertTextEquals("mailto:me@example.com")
        compose.onNodeWithTag(TestTags.Markdown.CANCEL).assertTextEquals("Cancel").performClick()
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Markdown.LINK_SHEET))
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun unsafeLinksAreJustText() {
        render("[click](javascript:alert(1)) and [file](file:///etc/passwd)")
        assertTrue(shows("click"))
        MarkdownTesting.clickText(compose, "click")
        MarkdownTesting.clickText(compose, "file")
        assertFalse(compose.exists(TestTags.Markdown.LINK_SHEET))
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun theLinkStyleMarksTheLinkText() {
        render("see [docs](https://example.com) now")
        val styles = MarkdownTesting.stylesOver(compose, "docs")
        assertTrue(styles.any { it.textDecoration?.contains(TextDecoration.Underline) == true })
        assertFalse(MarkdownTesting.stylesOver(compose, "now").any { it.textDecoration?.contains(TextDecoration.Underline) == true })
    }

    @Test
    fun textOutsideALinkDoesNotOpenTheSheet() {
        render("plain words before [a link](https://example.com)")
        MarkdownTesting.clickText(compose, "plain")
        assertFalse(compose.exists(TestTags.Markdown.LINK_SHEET))
        assertEquals(emptyList<String>(), opened)
    }

    private companion object {
        const val BLOCK = "markdown.block"
    }
}
