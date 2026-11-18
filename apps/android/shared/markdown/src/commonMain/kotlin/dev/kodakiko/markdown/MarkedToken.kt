package dev.kodakiko.markdown

internal enum class TokenType {
    SPACE,
    CODE,
    HEADING,
    HR,
    BLOCKQUOTE,
    LIST,
    LIST_ITEM,
    CHECKBOX,
    HTML,
    DEF,
    TABLE,
    PARAGRAPH,
    TEXT,
    ESCAPE,
    LINK,
    IMAGE,
    STRONG,
    EM,
    CODESPAN,
    BR,
    DEL,
}

internal class Token(var type: TokenType, var raw: String, var text: String = "") {
    var tokens: MutableList<Token>? = null
    var depth = 0
    var href = ""
    var title: String? = null
    var tag = ""
    var ordered = false
    var start = 0L
    var loose = false
    val items = mutableListOf<Token>()
    var task = false
    var checked: Boolean? = null
}

internal class LinkDefinition(val href: String, val title: String?)

internal class InlineJob(var src: String, val tokens: MutableList<Token>)

internal class CloserHints(maskedSrc: String) {
    var lastAsterisk = MarkedConstants.NOT_FOUND
    var lastUnderscore = MarkedConstants.NOT_FOUND
    var lastTilde = MarkedConstants.NOT_FOUND

    init {
        var index = 0
        while (index < maskedSrc.length) {
            val char = maskedSrc[index]
            if (char != MarkedConstants.ASTERISK && char != MarkedConstants.UNDERSCORE && char != MarkedConstants.TILDE) {
                index += 1
                continue
            }
            val previous = if (index == 0) MarkedConstants.NEWLINE else maskedSrc[index - 1]
            var end = index + 1
            while (end < maskedSrc.length && maskedSrc[end] == char) end += 1
            if (previous !in MarkedConstants.CLOSER_BLOCKERS) {
                when (char) {
                    MarkedConstants.TILDE -> lastTilde = index
                    MarkedConstants.ASTERISK -> lastAsterisk = index
                    else -> lastUnderscore = index
                }
            }
            index = end
        }
    }

    fun emStrong(delimiter: Char): Int = if (delimiter == MarkedConstants.ASTERISK) lastAsterisk else lastUnderscore
}
