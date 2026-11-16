package dev.kodakiko.ui.feature.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult

object MarkdownTesting {
    private val hasLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    fun textNodes(compose: AndroidComposeTestRule<*, *>, text: String): List<SemanticsNode> =
        compose.onAllNodes(hasText(text, substring = true) and hasLayout, useUnmergedTree = true).fetchSemanticsNodes()

    fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    fun annotated(node: SemanticsNode): AnnotatedString = layoutOf(node).layoutInput.text

    fun stylesOver(compose: AndroidComposeTestRule<*, *>, word: String): List<SpanStyle> {
        val node = textNodes(compose, word).first()
        val text = annotated(node)
        val start = text.text.indexOf(word)
        val end = start + word.length
        return text.spanStyles.filter { it.start <= start && it.end >= end }.map { it.item }
    }

    fun clickText(compose: AndroidComposeTestRule<*, *>, word: String) {
        val nodes = compose.onAllNodes(hasText(word, substring = true) and hasLayout, useUnmergedTree = true)
        val node = nodes.fetchSemanticsNodes().first()
        val layout = layoutOf(node)
        val start = layout.layoutInput.text.text.indexOf(word)
        val box = layout.getBoundingBox(start + word.length / 2)
        nodes[0].performTouchInput { click(Offset(box.center.x, box.center.y)) }
        compose.waitForIdle()
    }

    fun isHeading(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Heading) != null

    fun headingWith(compose: AndroidComposeTestRule<*, *>, text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
            generateSequence(node) { it.parent }.any(::isHeading)
        }
}
