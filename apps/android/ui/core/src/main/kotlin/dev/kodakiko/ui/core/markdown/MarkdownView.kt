package dev.kodakiko.ui.core.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import dev.kodakiko.markdown.Block
import dev.kodakiko.markdown.Inline
import dev.kodakiko.markdown.parseMarkdown
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet

private class MarkdownColors(val link: Color, val codeBackground: Color, val rule: Color)

@Composable
fun MarkdownView(source: String, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseMarkdown(source) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    if (blocks.isEmpty()) return
    val colors = MarkdownColors(
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        rule = MaterialTheme.colorScheme.outlineVariant,
    )
    Column(modifier = modifier.testTag(TestTags.Markdown.VIEW), verticalArrangement = Arrangement.spacedBy(MarkdownDimens.SMALL_SPACING)) {
        blocks.forEachIndexed { position, block ->
            Box(modifier = Modifier.fillMaxWidth().testTag(listItem(TestTags.Markdown.BLOCK, position))) {
                BlockView(block, 0, colors) { pending = it }
            }
        }
    }
    pending?.let { href ->
        LinkSheet(
            href = href,
            onOpen = {
                pending = null
                onOpenLink(href)
            },
            onCancel = { pending = null },
        )
    }
}

@Composable
private fun BlockView(block: Block, depth: Int, colors: MarkdownColors, onLink: (String) -> Unit) {
    val typography = MaterialTheme.typography
    when (block) {
        is Block.Heading -> Text(
            text = inlineText(block.children, colors, onLink),
            style = headingStyle(block.level).copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        is Block.Paragraph -> Text(text = inlineText(block.children, colors, onLink), style = typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        is Block.Code -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBackground, RoundedCornerShape(MarkdownDimens.LABEL_CORNER))
                .padding(MarkdownDimens.CODE_PADDING),
        ) {
            Text(text = block.text, style = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
        }
        is Block.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(MarkdownDimens.QUOTE_BAR).fillMaxHeight().background(colors.rule))
            Column(
                modifier = Modifier.padding(start = MarkdownDimens.SECTION_SPACING),
                verticalArrangement = Arrangement.spacedBy(MarkdownDimens.TINY_SPACING),
            ) {
                block.blocks.forEach { BlockView(it, depth + 1, colors, onLink) }
            }
        }
        is Block.ListBlock -> ListView(block, depth, colors, onLink)
        Block.Rule -> HorizontalDivider(thickness = MarkdownDimens.RULE, color = colors.rule)
    }
}

@Composable
private fun ListView(block: Block.ListBlock, depth: Int, colors: MarkdownColors, onLink: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(start = if (depth == 0) MarkdownDimens.NO_INDENT else MarkdownDimens.LIST_INDENT),
        verticalArrangement = Arrangement.spacedBy(MarkdownDimens.TINY_SPACING),
    ) {
        block.items.forEachIndexed { position, item ->
            val marker = when {
                item.checked == true -> stringResource(R.string.markdown_task_done)
                item.checked == false -> stringResource(R.string.markdown_task_open)
                block.ordered -> stringResource(R.string.markdown_ordered, (block.start + position).toString())
                else -> stringResource(R.string.markdown_bullet)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MarkdownDimens.SMALL_SPACING)) {
                Text(text = marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MarkdownDimens.TINY_SPACING)) {
                    item.blocks.forEach { BlockView(it, depth + 1, colors, onLink) }
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    MarkdownConstants.HEADING_LEVEL_TWO -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

private fun inlineText(inlines: List<Inline>, colors: MarkdownColors, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    appendInlines(inlines, colors, onLink)
}

private fun AnnotatedString.Builder.appendInlines(inlines: List<Inline>, colors: MarkdownColors, onLink: (String) -> Unit) {
    for (inline in inlines) {
        when (inline) {
            is Inline.Text -> append(inline.text)
            Inline.Break -> append(MarkdownConstants.LINE_BREAK)
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) { append(inline.text) }
            is Inline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(inline.children, colors, onLink) }
            is Inline.Em -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inline.children, colors, onLink) }
            is Inline.Del -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(inline.children, colors, onLink) }
            is Inline.Link -> appendLink(inline, colors, onLink)
        }
    }
}

private fun AnnotatedString.Builder.appendLink(link: Inline.Link, colors: MarkdownColors, onLink: (String) -> Unit) {
    val href = link.href
    if (href == null) {
        appendInlines(link.children, colors, onLink)
        return
    }
    pushLink(LinkAnnotation.Clickable(tag = href) { onLink(href) })
    withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) { appendInlines(link.children, colors, onLink) }
    pop()
}

@Composable
private fun LinkSheet(href: String, onOpen: () -> Unit, onCancel: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onCancel, modifier = Modifier.testTag(TestTags.Markdown.LINK_SHEET)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MarkdownDimens.SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(MarkdownDimens.SECTION_SPACING),
        ) {
            Text(text = stringResource(R.string.markdown_open_link_title), style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    text = href,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.Markdown.LINK_URL),
                )
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag(TestTags.Markdown.OPEN_LINK)) {
                Text(text = stringResource(R.string.markdown_open_link))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag(TestTags.Markdown.CANCEL)) {
                Text(text = stringResource(R.string.markdown_cancel))
            }
        }
    }
}
