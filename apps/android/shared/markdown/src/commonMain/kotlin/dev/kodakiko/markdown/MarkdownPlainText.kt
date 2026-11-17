package dev.kodakiko.markdown

fun markdownPlainText(source: String): String = blocksText(parseMarkdown(source))

private fun blocksText(blocks: List<Block>): String = blocks.map(::blockText).filter { it.isNotEmpty() }.joinToString(PlainTextConstants.BLOCK_SEPARATOR)

private fun blockText(block: Block): String = when (block) {
    is Block.Heading -> inlineText(block.children)
    is Block.Paragraph -> inlineText(block.children)
    is Block.Code -> block.text
    is Block.Quote -> blocksText(block.blocks)
    is Block.ListBlock -> block.items.mapIndexed { position, item -> itemText(block, position, item) }.joinToString(PlainTextConstants.LINE_SEPARATOR)
    Block.Rule -> ""
}

private fun marker(block: Block.ListBlock, position: Int, item: ListItem): String = when {
    item.checked == true -> PlainTextConstants.TASK_DONE
    item.checked == false -> PlainTextConstants.TASK_OPEN
    block.ordered -> (block.start + position).toString() + PlainTextConstants.ORDERED_SUFFIX
    else -> PlainTextConstants.BULLET
}

private fun itemText(block: Block.ListBlock, position: Int, item: ListItem): String {
    val body = item.blocks.map(::blockText).filter { it.isNotEmpty() }.joinToString(PlainTextConstants.LINE_SEPARATOR)
    val lines = body.split(PlainTextConstants.LINE_SEPARATOR)
    val first = marker(block, position, item) + PlainTextConstants.MARKER_GAP + lines.first()
    return (listOf(first) + lines.drop(1).map { PlainTextConstants.CONTINUATION + it }).joinToString(PlainTextConstants.LINE_SEPARATOR)
}

private fun inlineText(inlines: List<Inline>): String = buildString { appendInlines(inlines) }

private fun StringBuilder.appendInlines(inlines: List<Inline>) {
    for (inline in inlines) {
        when (inline) {
            is Inline.Text -> append(inline.text)
            is Inline.Code -> append(inline.text)
            Inline.Break -> append(PlainTextConstants.LINE_SEPARATOR)
            is Inline.Strong -> appendInlines(inline.children)
            is Inline.Em -> appendInlines(inline.children)
            is Inline.Del -> appendInlines(inline.children)
            is Inline.Link -> appendInlines(inline.children)
        }
    }
}
