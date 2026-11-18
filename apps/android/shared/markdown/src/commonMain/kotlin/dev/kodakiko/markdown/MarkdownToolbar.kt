package dev.kodakiko.markdown

enum class MarkdownAction(val wire: String) {
    BOLD("bold"),
    ITALIC("italic"),
    STRIKE("strike"),
    HEADING("heading"),
    BULLET("bullet"),
    CHECKLIST("checklist"),
    QUOTE("quote"),
    LINK("link"),
    TIME("time"),
}

data class TextSelection(val start: Int, val end: Int)

data class EditedText(val text: String, val selection: TextSelection)

private val WRAPS = mapOf(
    MarkdownAction.BOLD to ToolbarConstants.BOLD,
    MarkdownAction.ITALIC to ToolbarConstants.ITALIC,
    MarkdownAction.STRIKE to ToolbarConstants.STRIKE,
)

private val PREFIXES = mapOf(
    MarkdownAction.BULLET to ToolbarConstants.BULLET,
    MarkdownAction.CHECKLIST to ToolbarConstants.CHECKLIST,
    MarkdownAction.QUOTE to ToolbarConstants.QUOTE,
)

private val HEADINGS = listOf(ToolbarConstants.HEADING_ONE, ToolbarConstants.HEADING_TWO)

private fun wrap(text: String, selection: TextSelection, marker: String): EditedText {
    val before = text.substring(0, selection.start)
    val inner = text.substring(selection.start, selection.end)
    val after = text.substring(selection.end)
    val start = selection.start + marker.length
    return EditedText("$before$marker$inner$marker$after", TextSelection(start, start + inner.length))
}

private fun lineStart(text: String, position: Int): Int {
    val index = text.lastIndexOf(ToolbarConstants.LINE_BREAK, maxOf(position - 1, 0))
    return if (index == ToolbarConstants.NOT_FOUND) 0 else index + 1
}

private fun replaceLinePrefix(text: String, selection: TextSelection, remove: String, add: String): EditedText {
    val start = lineStart(text, selection.start)
    val shift = add.length - remove.length
    return EditedText(
        text.substring(0, start) + add + text.substring(minOf(start + remove.length, text.length)),
        TextSelection(maxOf(start, selection.start + shift), maxOf(start, selection.end + shift)),
    )
}

private fun cycleHeading(text: String, selection: TextSelection): EditedText {
    val line = text.substring(lineStart(text, selection.start))
    val current = HEADINGS.reversed().firstOrNull { line.startsWith(it) } ?: ""
    val index = HEADINGS.indexOf(current)
    val next = if (index == ToolbarConstants.NOT_FOUND) HEADINGS[0] else HEADINGS.getOrElse(index + 1) { "" }
    return replaceLinePrefix(text, selection, current, next)
}

fun applyMarkdownAction(action: MarkdownAction, text: String, selection: TextSelection, time: String = ""): EditedText {
    WRAPS[action]?.let { return wrap(text, selection, it) }
    PREFIXES[action]?.let { return replaceLinePrefix(text, selection, "", it) }
    if (action == MarkdownAction.HEADING) return cycleHeading(text, selection)
    if (action == MarkdownAction.LINK) {
        val label = text.substring(selection.start, selection.end)
        val head = text.substring(0, selection.start) + ToolbarConstants.LINK_LABEL_START + label + ToolbarConstants.LINK_URL_START
        return EditedText(
            head + ToolbarConstants.LINK_URL + ToolbarConstants.LINK_URL_END + text.substring(selection.end),
            TextSelection(head.length, head.length + ToolbarConstants.LINK_URL.length),
        )
    }
    val head = text.substring(0, selection.start) + time
    return EditedText(head + text.substring(selection.end), TextSelection(head.length, head.length))
}
