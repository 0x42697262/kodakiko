package dev.kodakiko.markdown

import dev.kodakiko.markdown.MarkedConstants.NEWLINE
import dev.kodakiko.markdown.MarkedConstants.SPACE
import dev.kodakiko.markdown.MarkedConstants.TAB

internal class SetextMatch(val value: String, val text: String, val underline: String)

internal object MarkedBlockScanner {
    private fun skipSpacesAndTabs(src: CharSequence, from: Int): Int {
        var index = from
        while (index < src.length && (src[index] == SPACE || src[index] == TAB)) index += 1
        return index
    }

    private fun lineEnd(src: CharSequence, from: Int): Int = src.indexOf(NEWLINE, from).let { if (it < 0) src.length else it }

    private fun blankLinesEnd(src: CharSequence, from: Int): Int {
        var position = from
        while (true) {
            val afterBlanks = skipSpacesAndTabs(src, position)
            when {
                afterBlanks < src.length && src[afterBlanks] == NEWLINE -> position = afterBlanks + 1
                afterBlanks == src.length -> return afterBlanks
                else -> return position
            }
        }
    }

    fun newline(src: CharSequence): Int = blankLinesEnd(src, 0)

    private fun codeIndentEnd(src: CharSequence, from: Int): Int {
        if (src.startsWith(MarkedConstants.TAB_SPACES, from)) return from + MarkedConstants.INDENTED_CODE_INDENT
        var index = from
        while (index - from < MarkedConstants.INDENT_BOUND_MAX && index < src.length && src[index] == SPACE) index += 1
        return if (index < src.length && src[index] == TAB) index + 1 else MarkedConstants.NOT_FOUND
    }

    fun code(src: CharSequence): Int {
        var position = 0
        while (position < src.length) {
            val contentStart = codeIndentEnd(src, position)
            if (contentStart == MarkedConstants.NOT_FOUND) break
            val contentEnd = lineEnd(src, contentStart)
            if (contentEnd == contentStart) break
            position = if (contentEnd < src.length) blankLinesEnd(src, contentEnd + 1) else contentEnd
        }
        return position
    }

    fun paragraphEnd(src: CharSequence, from: Int, interrupt: Regex): Int {
        var end = lineEnd(src, from)
        if (end == from) return MarkedConstants.NOT_FOUND
        while (end < src.length && end + 1 < src.length && src[end + 1] != NEWLINE && !interrupt.matchesAt(src, end + 1)) {
            end = lineEnd(src, end + 1)
        }
        return end
    }

    fun blockquote(src: CharSequence): Int {
        var position = 0
        while (position < src.length) {
            val marker = MarkedRules.quoteMarker.matchAt(src, position) ?: break
            val contentStart = marker.range.last + 1
            val contentEnd = if (MarkedRules.blankAhead.matchesAt(src, contentStart)) {
                lineEnd(src, contentStart)
            } else {
                paragraphEnd(src, contentStart, MarkedRules.blockquoteParagraphInterrupt)
            }
            position = if (contentEnd < src.length) contentEnd + 1 else contentEnd
        }
        return position
    }

    fun setextHeading(src: CharSequence): SetextMatch? {
        if (MarkedRules.lheadingStartBlocker.matchesAt(src, 0)) return null
        var position = 0
        while (true) {
            while (position < src.length && src[position] !in MarkedConstants.LINE_TERMINATORS) position += 1
            if (position >= src.length || src[position] != NEWLINE) return null
            if (position > 0) {
                val tail = MarkedRules.lheadingTail.matchAt(src, position)
                if (tail != null) return SetextMatch(src.substring(0, tail.range.last + 1), src.substring(0, position), tail.groupValues[1])
            }
            if (MarkedRules.lheadingLineBlocker.matchesAt(src, position + 1)) return null
            position += 1
        }
    }
}
