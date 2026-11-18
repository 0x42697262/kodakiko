package dev.kodakiko.markdown

import dev.kodakiko.markdown.JsRegexConstants.BACKSLASH
import dev.kodakiko.markdown.JsRegexConstants.CLASS_END
import dev.kodakiko.markdown.JsRegexConstants.CLASS_NEGATION
import dev.kodakiko.markdown.JsRegexConstants.CLASS_START
import dev.kodakiko.markdown.JsRegexConstants.QUANTIFIER_PATTERN

internal class JsRegexTranslator(private val source: String, private val multiline: Boolean) {
    private val out = StringBuilder()
    private var index = 0
    private var inClass = false
    private var rangeJustClosed = false

    fun translate(): String {
        while (index < source.length) {
            val char = source[index]
            when {
                char == BACKSLASH -> escape()
                inClass -> classChar(char)
                char == CLASS_START -> openClass()
                char == JsRegexConstants.ANY -> out.append(JsRegexConstants.JS_DOT)
                char == JsRegexConstants.END -> out.append(if (multiline) JsRegexConstants.JS_LINE_END else JsRegexConstants.JS_INPUT_END)
                char == JsRegexConstants.START -> out.append(if (multiline) JsRegexConstants.JS_LINE_START else JsRegexConstants.START.toString())
                char == JsRegexConstants.BRACE_OPEN -> brace()
                else -> out.append(char)
            }
            index += 1
        }
        return out.toString()
    }

    private fun openClass() {
        inClass = true
        rangeJustClosed = false
        out.append(CLASS_START)
        if (source.getOrNull(index + 1) == CLASS_NEGATION) {
            out.append(CLASS_NEGATION)
            index += 1
        }
    }

    private fun classChar(char: Char) {
        val wasRangeEnd = rangeJustClosed
        rangeJustClosed = false
        when (char) {
            CLASS_END -> {
                inClass = false
                out.append(char)
            }
            CLASS_START, JsRegexConstants.AMPERSAND -> out.append(BACKSLASH).append(char)
            JsRegexConstants.RANGE -> {
                val next = source.getOrNull(index + 1)
                val previous = source.getOrNull(index - 1)
                val afterNegation = previous == CLASS_NEGATION && source.getOrNull(index - JsRegexConstants.NEGATED_CLASS_START_DISTANCE) == CLASS_START
                val literal = wasRangeEnd || next == CLASS_END || previous == CLASS_START || afterNegation
                if (literal) {
                    out.append(BACKSLASH).append(char)
                } else {
                    out.append(char)
                    rangeEndFollows()
                }
            }
            else -> out.append(char)
        }
    }

    private fun rangeEndFollows() {
        index += 1
        if (source[index] == BACKSLASH) escape() else out.append(source[index])
        rangeJustClosed = true
    }

    private fun brace() {
        val quantifier = QUANTIFIER_PATTERN.matchAt(source, index)
        if (quantifier == null) {
            out.append(BACKSLASH).append(JsRegexConstants.BRACE_OPEN)
        } else {
            out.append(quantifier.value)
            index = quantifier.range.last
        }
    }

    private fun escape() {
        val next = source[index + 1]
        index += 1
        val replacement = if (inClass) JsRegexConstants.CLASS_ESCAPES[next] else JsRegexConstants.ESCAPES[next]
        when {
            replacement != null -> out.append(replacement)
            next == JsRegexConstants.VERTICAL_TAB_ESCAPE -> out.append(JsRegexConstants.VERTICAL_TAB)
            next == JsRegexConstants.BOUNDARY_ESCAPE && inClass -> out.append(JsRegexConstants.BACKSPACE)
            next == JsRegexConstants.UNICODE_PROPERTY_ESCAPE || next == JsRegexConstants.UNICODE_PROPERTY_NEGATED_ESCAPE -> {
                val end = source.indexOf(JsRegexConstants.BRACE_CLOSE, index)
                out.append(BACKSLASH).append(source, index, end + 1)
                index = end
            }
            else -> out.append(BACKSLASH).append(next)
        }
    }
}

internal fun jsRegex(source: String, flags: String = ""): Regex {
    val translated = JsRegexTranslator(source, JsRegexConstants.MULTILINE_FLAG in flags).translate()
    val options = if (JsRegexConstants.IGNORE_CASE_FLAG in flags) setOf(RegexOption.IGNORE_CASE) else emptySet()
    return Regex(translated, options)
}
