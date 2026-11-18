package dev.kodakiko.markdown

import dev.kodakiko.js.jsSlice
import dev.kodakiko.markdown.MarkedConstants.NEWLINE
import dev.kodakiko.markdown.MarkedConstants.NEWLINE_TEXT

internal class MarkedLexer {
    val links = LinkedHashMap<String, LinkDefinition>()
    val inlineQueue = mutableListOf<InlineJob>()
    var inLink = false
    var inRawBlock = false
    var linkEmitted = false
    var linkParenPossible = true
    var top = true
    private val tokenizer = MarkedTokenizer(this)

    fun lex(source: String): List<Token> {
        val src = MarkedRules.carriageReturn.replace(source) { NEWLINE_TEXT }
        val tokens = blockTokens(src, mutableListOf())
        var index = 0
        while (index < inlineQueue.size) {
            val next = inlineQueue[index]
            inRawBlock = false
            inlineTokens(next.src, next.tokens)
            index += 1
        }
        inlineQueue.clear()
        return tokens
    }

    fun inline(src: String): MutableList<Token> {
        val tokens = mutableListOf<Token>()
        inlineQueue.add(InlineJob(src, tokens))
        return tokens
    }

    private fun appendRaw(last: Token, raw: String) {
        last.raw += (if (last.raw.endsWith(NEWLINE)) "" else NEWLINE_TEXT) + raw
    }

    private fun isTextLike(token: Token?): Boolean = token?.type == TokenType.PARAGRAPH || token?.type == TokenType.TEXT

    fun blockTokens(source: String, tokens: MutableList<Token>, lastParagraphClippedArg: Boolean = false): MutableList<Token> {
        var src = TextSlice.of(source)
        var lastParagraphClipped = lastParagraphClippedArg
        var srcLength = Int.MAX_VALUE
        while (src.isNotEmpty()) {
            if (src.length < srcLength) srcLength = src.length else infiniteLoop(src)
            val last = tokens.lastOrNull()
            val space = tokenizer.space(src)
            if (space != null) {
                src = src.dropStart(space.raw.length)
                if (space.raw.length == 1 && last != null) last.raw += NEWLINE_TEXT else tokens.add(space)
                continue
            }
            val code = tokenizer.code(src)
            if (code != null) {
                src = src.dropStart(code.raw.length)
                if (last != null && isTextLike(last)) {
                    appendRaw(last, code.raw)
                    last.text += NEWLINE_TEXT + code.text
                    inlineQueue.last().src = last.text
                } else {
                    tokens.add(code)
                }
                continue
            }
            val simple = tokenizer.fences(src) ?: tokenizer.heading(src) ?: tokenizer.hr(src) ?: tokenizer.blockquote(src) ?: tokenizer.list(src) ?: tokenizer.html(src)
            if (simple != null) {
                src = src.dropStart(simple.raw.length)
                tokens.add(simple)
                continue
            }
            val def = tokenizer.def(src)
            if (def != null) {
                src = src.dropStart(def.raw.length)
                if (last != null && isTextLike(last)) {
                    appendRaw(last, def.raw)
                    last.text += NEWLINE_TEXT + def.raw
                    inlineQueue.last().src = last.text
                } else {
                    if (!links.containsKey(def.tag)) links[def.tag] = LinkDefinition(def.href, def.title)
                    tokens.add(def)
                }
                continue
            }
            val tableOrHeading = tokenizer.table(src) ?: tokenizer.lheading(src)
            if (tableOrHeading != null) {
                src = src.dropStart(tableOrHeading.raw.length)
                tokens.add(tableOrHeading)
                continue
            }
            val paragraph = if (top) tokenizer.paragraph(src) else null
            if (paragraph != null) {
                if (lastParagraphClipped && last?.type == TokenType.PARAGRAPH) {
                    appendRaw(last, paragraph.raw)
                    last.text += NEWLINE_TEXT + paragraph.text
                    inlineQueue.removeAt(inlineQueue.size - 1)
                    inlineQueue.last().src = last.text
                } else {
                    tokens.add(paragraph)
                }
                lastParagraphClipped = false
                src = src.dropStart(paragraph.raw.length)
                continue
            }
            val text = tokenizer.text(src)
            if (text != null) {
                src = src.dropStart(text.raw.length)
                if (last?.type == TokenType.TEXT) {
                    appendRaw(last, text.raw)
                    last.text += NEWLINE_TEXT + text.text
                    inlineQueue.removeAt(inlineQueue.size - 1)
                    inlineQueue.last().src = last.text
                } else {
                    tokens.add(text)
                }
                continue
            }
            infiniteLoop(src)
        }
        top = true
        return tokens
    }

    fun inlineTokens(src: String, tokens: MutableList<Token> = mutableListOf()): MutableList<Token> {
        val previous = linkParenPossible
        linkParenPossible = previous && src.contains(MarkedConstants.CLOSE_PAREN)
        try {
            val maskedSrc = if (src.any { it == MarkedConstants.ASTERISK || it == MarkedConstants.UNDERSCORE || it == MarkedConstants.TILDE }) maskSrc(src) else src
            return tokenizeInlines(src, maskedSrc, CloserHints(maskedSrc), tokens)
        } finally {
            linkParenPossible = previous
        }
    }

    private fun tokenizeInlines(source: String, maskedSrc: String, hints: CloserHints, tokens: MutableList<Token>): MutableList<Token> {
        var src = TextSlice.of(source)
        var keepPrevChar = false
        var prevChar = ""
        var srcLength = Int.MAX_VALUE
        while (src.isNotEmpty()) {
            if (src.length < srcLength) srcLength = src.length else infiniteLoop(src)
            if (!keepPrevChar) prevChar = ""
            keepPrevChar = false
            val first = tokenizer.escape(src) ?: tokenizer.tag(src) ?: tokenizer.link(src)
            if (first != null) {
                src = src.dropStart(first.raw.length)
                tokens.add(first)
                continue
            }
            val reference = tokenizer.reflink(src, links)
            if (reference != null) {
                src = src.dropStart(reference.raw.length)
                val last = tokens.lastOrNull()
                if (reference.type == TokenType.TEXT && last?.type == TokenType.TEXT) {
                    last.raw += reference.raw
                    last.text += reference.text
                } else {
                    tokens.add(reference)
                }
                continue
            }
            val formatted = tokenizer.emStrong(src, maskedSrc, prevChar, hints) ?: tokenizer.codespan(src) ?: tokenizer.br(src)
                ?: tokenizer.del(src, maskedSrc, prevChar, hints) ?: tokenizer.autolink(src) ?: (if (!inLink) tokenizer.url(src) else null)
            if (formatted != null) {
                src = src.dropStart(formatted.raw.length)
                tokens.add(formatted)
                continue
            }
            val text = tokenizer.inlineText(src)
            if (text != null) {
                src = src.dropStart(text.raw.length)
                if (!text.raw.endsWith(MarkedConstants.UNDERSCORE)) prevChar = sliceFromEnd(text.raw, 1)
                keepPrevChar = true
                val last = tokens.lastOrNull()
                if (last?.type == TokenType.TEXT) {
                    last.raw += text.raw
                    last.text += text.text
                } else {
                    tokens.add(text)
                }
                continue
            }
            infiniteLoop(src)
        }
        return tokens
    }

    fun linkInText(text: String): Boolean {
        if (!text.contains(MarkedConstants.OPEN_BRACKET)) return false
        for (match in MarkedRules.blockSkip.findAll(text)) {
            if (MarkedRules.link.startsAt(match.value) && text.getOrNull(match.range.first - 1) != MarkedConstants.BANG) return true
        }
        for (match in MarkedRules.reflinkSearch.findAll(text)) {
            val value = match.value
            val refStart = value.lastIndexOf(MarkedConstants.OPEN_BRACKET)
            if (value.first() == MarkedConstants.BANG || !links.containsKey(normalizeLabel(jsSlice(value, refStart + 1, -1)))) continue
            if (refStart > 1 && linkInText(value.substring(1, refStart - 1))) continue
            return true
        }
        return false
    }

    private fun maskReflink(value: String): String {
        val refStart = value.lastIndexOf(MarkedConstants.OPEN_BRACKET)
        if (!links.containsKey(normalizeLabel(jsSlice(value, refStart + 1, -1)))) return value
        if (refStart > 1 && value.first() != MarkedConstants.BANG) {
            val text = value.substring(1, refStart - 1)
            if (linkInText(text)) {
                return MarkedConstants.OPEN_BRACKET_TEXT + MarkedRules.reflinkSearch.replace(text) { maskReflink(it.value) } +
                    MarkedConstants.LINK_SEPARATOR + mask(value.length - refStart - MarkedConstants.PAIR) + MarkedConstants.CLOSE_BRACKET_TEXT
            }
        }
        return MarkedConstants.OPEN_BRACKET_TEXT + mask(value.length - MarkedConstants.PAIR) + MarkedConstants.CLOSE_BRACKET_TEXT
    }

    private fun mask(count: Int): String = MarkedConstants.MASK_CHAR.toString().repeat(maxOf(count, 0))

    private fun maskSrc(src: String): String {
        var masked = src
        if (src.contains(MarkedConstants.OPEN_BRACKET)) masked = MarkedRules.reflinkSearch.replace(masked) { maskReflink(it.value) }
        masked = MarkedRules.anyPunctuationOutsideCode.replace(masked) { match ->
            val code = match.group(1)
            when {
                match.group(RegexGroups.SECOND).truthy() -> MarkedConstants.PUNCTUATION_MASK_TEXT.repeat(match.value.length)
                code.truthy() -> code + MarkedRules.backtick.replace(match.value.substring(code!!.length, match.value.length - code.length)) {
                    MarkedConstants.PUNCTUATION_MASK_TEXT
                } + code
                else -> match.value
            }
        }
        return MarkedRules.blockSkip.replace(masked) { match ->
            MarkedConstants.OPEN_BRACKET_TEXT + mask(match.value.length - MarkedConstants.PAIR) + MarkedConstants.CLOSE_BRACKET_TEXT
        }
    }

    private fun infiniteLoop(src: CharSequence): Nothing = throw IllegalStateException(MarkedConstants.INFINITE_LOOP + src[0].code)
}
