package dev.kodakiko.markdown

import dev.kodakiko.js.jsTrim

private fun rawText(token: Token): String = token.raw.trimEnd(MarkedConstants.NEWLINE)

private fun safeHref(href: String): String? {
    val scheme = ParseConstants.SCHEME_PATTERN.find(jsTrim(href).lowercase())?.groupValues?.get(ParseConstants.SCHEME_GROUP)
    return if (scheme != null && scheme in SAFE_LINK_SCHEMES) href else null
}

private fun headingLevel(depth: Int): Int = minOf(maxOf(depth, ParseConstants.MIN_HEADING_LEVEL), MAX_HEADING_LEVEL)

private fun inlines(tokens: List<Token>?): List<Inline> = tokens.orEmpty().flatMap(::inline)

private fun inline(token: Token): List<Inline> = when (token.type) {
    TokenType.TEXT, TokenType.ESCAPE -> {
        val children = token.tokens
        if (!children.isNullOrEmpty()) inlines(children) else listOf(Inline.Text(token.text))
    }
    TokenType.STRONG -> listOf(Inline.Strong(inlines(token.tokens)))
    TokenType.EM -> listOf(Inline.Em(inlines(token.tokens)))
    TokenType.DEL -> listOf(Inline.Del(inlines(token.tokens)))
    TokenType.CODESPAN -> listOf(Inline.Code(token.text))
    TokenType.BR -> listOf(Inline.Break)
    TokenType.LINK -> listOf(Inline.Link(safeHref(token.href), inlines(token.tokens)))
    TokenType.IMAGE -> listOf(Inline.Text(token.text.ifEmpty { MarkdownText.IMAGE_PLACEHOLDER }))
    else -> listOf(Inline.Text(token.raw))
}

private fun plain(token: Token): Block = Block.Paragraph(listOf(Inline.Text(rawText(token))))

private fun blocks(tokens: List<Token>, depth: Int): List<Block> = tokens.flatMap { block(it, depth) }

private fun block(token: Token, depth: Int): List<Block> = when (token.type) {
    TokenType.SPACE -> emptyList()
    TokenType.HEADING -> listOf(Block.Heading(headingLevel(token.depth), inlines(token.tokens)))
    TokenType.PARAGRAPH -> listOf(Block.Paragraph(inlines(token.tokens)))
    TokenType.TEXT -> listOf(Block.Paragraph(token.tokens?.let(::inlines) ?: listOf(Inline.Text(token.text))))
    TokenType.CODE -> listOf(Block.Code(token.text))
    TokenType.HR -> listOf(Block.Rule)
    TokenType.BLOCKQUOTE -> if (depth >= MARKDOWN_MAX_DEPTH) listOf(plain(token)) else listOf(Block.Quote(blocks(token.tokens.orEmpty(), depth + 1)))
    TokenType.LIST -> if (depth >= MARKDOWN_MAX_DEPTH) listOf(plain(token)) else listOf(list(token, depth))
    else -> listOf(plain(token))
}

private fun list(token: Token, depth: Int): Block = Block.ListBlock(
    ordered = token.ordered,
    start = if (token.ordered) token.start else ParseConstants.UNORDERED_START,
    items = token.items.map { item ->
        ListItem(
            checked = if (item.task) item.checked == true else null,
            blocks = blocks(item.tokens.orEmpty().filter { it.type != TokenType.CHECKBOX }, depth + 1),
        )
    },
)

fun parseMarkdown(source: String): List<Block> = blocks(MarkedLexer().lex(source), 0)
