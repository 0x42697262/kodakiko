package dev.kodakiko.markdown

import dev.kodakiko.js.codePointsOf
import dev.kodakiko.js.isJsWhitespace
import dev.kodakiko.js.jsSlice
import dev.kodakiko.js.jsTrim
import dev.kodakiko.markdown.MarkedConstants.NEWLINE_TEXT

internal fun MatchResult.group(index: Int): String? = if (index < groups.size) groups[index]?.value else null

internal fun String?.truthy(): Boolean = !isNullOrEmpty()

internal fun Regex.test(text: CharSequence): Boolean = containsMatchIn(text)

internal fun Regex.exec(text: CharSequence): MatchResult? = find(text)

internal fun Regex.lookingAt(text: CharSequence): MatchResult? = matchAt(text, 0)

internal fun Regex.startsAt(text: CharSequence): Boolean = matchesAt(text, 0)

internal fun Regex.replaceFirstMatch(text: String, transform: (MatchResult) -> String): String {
    val match = find(text) ?: return text
    return text.substring(0, match.range.first) + transform(match) + text.substring(match.range.last + 1)
}

internal fun Regex.removeFirst(text: String): String = replaceFirstMatch(text) { "" }

internal fun Regex.removeAll(text: String): String = replace(text) { "" }

internal fun Regex.replaceAllWithGroup(text: String, group: Int): String = replace(text) { it.group(group).orEmpty() }

internal fun jsTrimEnd(text: String): String = text.trimEnd { isJsWhitespace(it) }

internal fun jsTrimStart(text: String): String = text.trimStart { isJsWhitespace(it) }

internal fun codePointLength(text: String): Int = codePointsOf(text).size

internal fun firstLine(text: CharSequence): String = text.indexOf(MarkedConstants.NEWLINE).let { if (it < 0) text.toString() else text.substring(0, it) }

internal fun searchNonSpace(text: String): Int = text.indexOfFirst { it != MarkedConstants.SPACE }

internal fun rtrim(text: String, char: Char): String = text.trimEnd { it == char }

internal fun trimTrailingBlankLines(text: String): String {
    val lines = text.split(NEWLINE_TEXT)
    var end = lines.size - 1
    while (end >= 0 && MarkedRules.blankLine.test(lines[end])) end -= 1
    if (lines.size - end <= MarkedConstants.PAIR) return text
    return lines.subList(0, end + 1).joinToString(NEWLINE_TEXT)
}

internal fun normalizeLabel(label: String): String =
    MarkedRules.multipleSpace.replace(jsTrim(label)) { MarkedConstants.SPACE.toString() }.lowercase().uppercase().lowercase()

internal fun findClosingBracket(text: String, brackets: String): Int {
    val open = brackets[0]
    val close = brackets[1]
    if (text.indexOf(open) == MarkedConstants.NOT_FOUND && text.indexOf(close) == MarkedConstants.NOT_FOUND) return MarkedConstants.NOT_FOUND
    var level = 0
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            MarkedConstants.BACKSLASH -> index += 1
            open -> level += 1
            close -> {
                level -= 1
                if (level < 0) return index
            }
        }
        index += 1
    }
    return if (level > 0) MarkedConstants.CLOSING_BRACKET_UNBALANCED else MarkedConstants.NOT_FOUND
}

internal fun expandTabs(line: String, indent: Int = 0): String {
    var column = indent
    val expanded = StringBuilder()
    for (char in codePointsOf(line)) {
        if (char == MarkedConstants.TAB.toString()) {
            val added = MarkedConstants.TAB_STOP - (column % MarkedConstants.TAB_STOP)
            repeat(added) { expanded.append(MarkedConstants.SPACE) }
            column += added
        } else {
            expanded.append(char)
            column += 1
        }
    }
    return expanded.toString()
}

internal fun decodeNumericCharacterReferences(text: String): String {
    if (!MarkedRules.numericCharacterReference.test(text)) return text
    return MarkedRules.numericCharacterReferenceOrAmpersand.replace(text) { match ->
        val decimal = match.group(1)
        val hex = match.group(RegexGroups.SECOND)
        if (decimal == null && hex == null) return@replace MarkedConstants.AMPERSAND_ENTITY
        val code = decimal?.toInt(MarkedConstants.DECIMAL_RADIX) ?: hex!!.toInt(MarkedConstants.HEX_RADIX)
        when {
            code == 0 || code > MarkedConstants.MAX_CODE_POINT || (code >= Char.MIN_SURROGATE.code && code <= Char.MAX_SURROGATE.code) ->
                MarkedConstants.REPLACEMENT_CHARACTER.toString()
            code == MarkedConstants.AMPERSAND_CODE -> MarkedConstants.AMPERSAND_ENTITY
            else -> codePointString(code)
        }
    }
}

private fun codePointString(code: Int): String {
    if (code < MarkedConstants.SUPPLEMENTARY_BASE) return code.toChar().toString()
    val offset = code - MarkedConstants.SUPPLEMENTARY_BASE
    val high = Char.MIN_HIGH_SURROGATE + (offset shr MarkedConstants.SURROGATE_SHIFT)
    val low = Char.MIN_LOW_SURROGATE + (offset and MarkedConstants.SURROGATE_MASK)
    return charArrayOf(high, low).concatToString()
}

internal fun splitCells(tableRow: String, count: Int? = null): List<String> {
    val row = MarkedRules.findPipe.replace(tableRow) { match ->
        var escaped = false
        var current = match.range.first - 1
        while (current >= 0 && tableRow[current] == MarkedConstants.BACKSLASH) {
            escaped = !escaped
            current -= 1
        }
        if (escaped) MarkedConstants.PIPE_TEXT else MarkedConstants.SPACED_PIPE
    }
    val cells = MarkedRules.splitPipe.split(row).toMutableList()
    if (jsTrim(cells[0]).isEmpty()) cells.removeAt(0)
    if (cells.isNotEmpty() && jsTrim(cells.last()).isEmpty()) cells.removeAt(cells.size - 1)
    if (count != null && count != 0) {
        while (cells.size > count) cells.removeAt(cells.size - 1)
        while (cells.size < count) cells.add("")
    }
    return cells.map { MarkedRules.slashPipe.replace(jsTrim(it)) { MarkedConstants.PIPE_TEXT } }
}

internal fun sliceFromEnd(text: String, count: Int): String = jsSlice(text, -count)
