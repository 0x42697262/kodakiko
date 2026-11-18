package dev.kodakiko.markdown

import dev.kodakiko.js.codePointsOf
import dev.kodakiko.js.jsSlice
import dev.kodakiko.js.jsTrim
import dev.kodakiko.markdown.MarkedConstants.NEWLINE
import dev.kodakiko.markdown.MarkedConstants.NEWLINE_TEXT

internal class MarkedTokenizer(private val lexer: MarkedLexer) {
    fun space(src: CharSequence): Token? {
        val end = MarkedBlockScanner.newline(src)
        return if (end > 0) Token(TokenType.SPACE, src.substring(0, end)) else null
    }

    fun code(src: CharSequence): Token? {
        val end = MarkedBlockScanner.code(src)
        if (end == 0) return null
        val raw = trimTrailingBlankLines(src.substring(0, end))
        return Token(TokenType.CODE, raw, MarkedRules.codeRemoveIndent.removeAll(raw))
    }

    fun fences(src: CharSequence): Token? {
        val cap = MarkedRules.fences.lookingAt(src) ?: return null
        val raw = cap.value
        return Token(TokenType.CODE, raw, indentCodeCompensation(raw, cap.group(RegexGroups.THIRD).orEmpty()))
    }

    private fun indentCodeCompensation(raw: String, text: String): String {
        val indentToCode = MarkedRules.indentCodeCompensation.lookingAt(raw)?.group(1) ?: return text
        return text.split(NEWLINE_TEXT).joinToString(NEWLINE_TEXT) { node ->
            val indentInNode = MarkedRules.beginningSpace.lookingAt(node)?.value ?: return@joinToString node
            node.substring(minOf(indentInNode.length, indentToCode.length))
        }
    }

    fun heading(src: CharSequence): Token? {
        val cap = MarkedRules.heading.lookingAt(src) ?: return null
        var text = jsTrim(cap.group(RegexGroups.SECOND).orEmpty())
        if (MarkedRules.endingHash.test(text)) {
            val trimmed = rtrim(text, MarkedConstants.HASH)
            if (trimmed.isEmpty() || MarkedRules.endingSpaceTabChar.test(trimmed)) text = jsTrim(trimmed)
        }
        return Token(TokenType.HEADING, rtrim(cap.value, NEWLINE), text).apply {
            depth = cap.group(1)!!.length
            tokens = lexer.inline(text)
        }
    }

    fun hr(src: CharSequence): Token? {
        val cap = MarkedRules.hr.lookingAt(src) ?: return null
        return Token(TokenType.HR, rtrim(cap.value, NEWLINE))
    }

    fun blockquote(src: CharSequence): Token? {
        val end = MarkedBlockScanner.blockquote(src)
        if (end == 0) return null
        var lines = rtrim(src.substring(0, end), NEWLINE).split(NEWLINE_TEXT)
        var raw = ""
        var text = ""
        val tokens = mutableListOf<Token>()
        while (lines.isNotEmpty()) {
            var inBlockquote = false
            val currentLines = mutableListOf<String>()
            var index = 0
            while (index < lines.size) {
                if (MarkedRules.blockquoteStart.test(lines[index])) {
                    currentLines.add(lines[index])
                    inBlockquote = true
                } else if (!inBlockquote) {
                    currentLines.add(lines[index])
                } else {
                    break
                }
                index += 1
            }
            lines = lines.subList(index, lines.size)
            val currentRaw = currentLines.joinToString(NEWLINE_TEXT)
            val withTabs = MarkedRules.blockquoteMarkerWhitespace.replace(currentRaw) { match ->
                val marker = match.group(1)!!
                marker + expandTabs(match.group(RegexGroups.SECOND)!!, marker.length)
            }
            val withSetext = MarkedRules.blockquoteSetextReplace.replace(withTabs) { NEWLINE_TEXT + MarkedConstants.SETEXT_CONTINUATION_INDENT + it.group(1).orEmpty() }
            val currentText = MarkedRules.blockquoteSetextReplace2.removeAll(withSetext)
            raw = if (raw.isNotEmpty()) "$raw$NEWLINE_TEXT$currentRaw" else currentRaw
            text = if (text.isNotEmpty()) "$text$NEWLINE_TEXT$currentText" else currentText
            val outerTop = lexer.top
            lexer.top = true
            lexer.blockTokens(currentText, tokens, true)
            lexer.top = outerTop
            if (lines.isEmpty()) break
            val last = tokens.lastOrNull()
            when (last?.type) {
                TokenType.CODE, TokenType.HEADING, TokenType.HR, TokenType.HTML, TokenType.TABLE -> break
                TokenType.BLOCKQUOTE -> {
                    val continuation = lines.joinToString(NEWLINE_TEXT)
                    val newText = last.raw + NEWLINE_TEXT + MarkedRules.blockquoteSetextReplace2.removeAll(continuation)
                    val newToken = blockquote(newText)!!
                    tokens[tokens.size - 1] = newToken
                    val leftover = MarkedRules.leadingNewline.removeFirst(newText.substring(newToken.raw.length))
                    val leftoverCount = if (leftover.isNotEmpty()) leftover.split(NEWLINE_TEXT).size else 0
                    val consumed = if (leftoverCount != 0) lines.subList(0, maxOf(lines.size - leftoverCount, 0)) else lines
                    if (consumed.isNotEmpty()) raw = "$raw$NEWLINE_TEXT${consumed.joinToString(NEWLINE_TEXT)}"
                    text = text.substring(0, text.length - last.text.length) + newToken.text
                    lines = lines.subList(consumed.size, lines.size)
                    if (lines.isNotEmpty() && MarkedRules.blockquoteStart.test(lines[0])) continue
                    break
                }
                TokenType.LIST -> {
                    val newText = last.raw + NEWLINE_TEXT + lines.joinToString(NEWLINE_TEXT)
                    val newToken = list(newText)!!
                    tokens[tokens.size - 1] = newToken
                    val consumedCount = if (newToken.raw.length > last.raw.length) {
                        newText.substring(last.raw.length + 1, newToken.raw.length).split(NEWLINE_TEXT).size
                    } else {
                        0
                    }
                    if (consumedCount > 0) {
                        val consumed = lines.subList(0, minOf(consumedCount, lines.size)).joinToString(NEWLINE_TEXT)
                        raw = "$raw$NEWLINE_TEXT$consumed"
                        text = "$text$NEWLINE_TEXT$consumed"
                    }
                    lines = lines.subList(minOf(consumedCount, lines.size), lines.size)
                    continue
                }
                else -> {}
            }
        }
        return Token(TokenType.BLOCKQUOTE, raw, text).apply { this.tokens = tokens }
    }

    fun list(source: CharSequence): Token? {
        var cap = MarkedRules.list.lookingAt(source) ?: return null
        var src = TextSlice.of(source)
        val listSrc = src
        fun taken() = listSrc.length - src.length
        var bullet = jsTrim(cap.group(1)!!)
        val ordered = bullet.length > 1
        val list = Token(TokenType.LIST, "").apply {
            this.ordered = ordered
            start = if (ordered) bullet.dropLast(MarkedConstants.ORDERED_START_DELIMITER_LENGTH).toLong() else 0L
        }
        bullet = if (ordered) MarkedConstants.ORDERED_BULLET_PATTERN + "\\" + bullet.last() else "\\" + bullet
        val itemRegex = MarkedRules.listItem(bullet)
        var endsWithBlankLine = false
        while (src.isNotEmpty()) {
            var endEarly = false
            var itemContents = ""
            cap = itemRegex.lookingAt(src) ?: break
            if (MarkedRules.hr.startsAt(src)) break
            val itemStart = taken()
            src = src.dropStart(cap.value.length)
            val firstLine = firstLine(cap.group(RegexGroups.SECOND)!!)
            val bulletIndent = cap.group(1)!!.length
            var line = MarkedRules.leadingSpaceTab.replaceFirstMatch(firstLine) { expandTabs(it.value, bulletIndent) }
            var nextLine = firstLine(src)
            var blankLine = jsTrim(line).isEmpty()
            var indent: Int
            if (blankLine) {
                indent = bulletIndent + 1
            } else {
                indent = searchNonSpace(line)
                indent = if (indent > MarkedConstants.ITEM_INDENT_MAX) 1 else indent
                itemContents = line.substring(indent)
                indent += bulletIndent
            }
            if (blankLine && MarkedRules.blankLine.test(nextLine)) {
                src = src.dropStart(nextLine.length + 1)
                endEarly = true
            }
            if (!endEarly) {
                val nextBullet = MarkedRules.nextBullet(indent)
                val hrBegin = MarkedRules.hrBegin(indent)
                val fencesBegin = MarkedRules.fencesBegin(indent)
                val headingBegin = MarkedRules.headingBegin(indent)
                val htmlBegin = MarkedRules.htmlBegin(indent)
                val blockquoteBegin = MarkedRules.blockquoteBegin(indent)
                while (src.isNotEmpty()) {
                    val rawLine = firstLine(src)
                    nextLine = rawLine
                    val nextLineWithoutTabs = MarkedRules.leadingSpaceTab.replaceFirstMatch(nextLine) { expandTabs(it.value) }
                    if (fencesBegin.test(nextLine) || headingBegin.test(nextLine) || htmlBegin.test(nextLine) || blockquoteBegin.test(nextLine) ||
                        nextBullet.test(nextLine) || hrBegin.test(nextLine)
                    ) {
                        break
                    }
                    if (searchNonSpace(nextLineWithoutTabs) >= indent || jsTrim(nextLine).isEmpty()) {
                        itemContents += NEWLINE_TEXT + nextLineWithoutTabs.substring(minOf(indent, nextLineWithoutTabs.length))
                    } else {
                        if (blankLine) break
                        if (searchNonSpace(line.replace(MarkedConstants.TAB.toString(), MarkedConstants.TAB_SPACES)) >= MarkedConstants.INDENTED_CODE_INDENT) break
                        if (fencesBegin.test(line) || headingBegin.test(line) || htmlBegin.test(line) || hrBegin.test(line)) break
                        val setextContinuation = MarkedRules.setextUnderline.test(nextLine) && MarkedBlockScanner.setextHeading("$line$NEWLINE_TEXT$nextLine") != null
                        val continuation = if (setextContinuation) {
                            MarkedConstants.SETEXT_CONTINUATION_INDENT + MarkedRules.leadingIndent.removeFirst(nextLineWithoutTabs)
                        } else {
                            nextLine
                        }
                        itemContents += NEWLINE_TEXT + continuation
                    }
                    blankLine = jsTrim(nextLine).isEmpty()
                    src = src.dropStart(rawLine.length + 1)
                    line = nextLineWithoutTabs.substring(minOf(indent, nextLineWithoutTabs.length))
                }
            }
            val raw = listSrc.substring(itemStart, taken())
            if (!list.loose) {
                if (endsWithBlankLine) {
                    list.loose = true
                } else if (MarkedRules.doubleBlankLine.test(raw)) {
                    endsWithBlankLine = true
                }
            }
            list.items.add(
                Token(TokenType.LIST_ITEM, raw, itemContents).apply {
                    task = MarkedRules.listIsTask.startsAt(itemContents)
                    tokens = mutableListOf()
                },
            )
        }
        val lastItem = list.items.lastOrNull() ?: return null
        lastItem.raw = jsTrimEnd(lastItem.raw)
        lastItem.text = jsTrimEnd(lastItem.text)
        list.raw = jsTrimEnd(listSrc.substring(0, taken()))
        for (item in list.items) {
            lexer.top = false
            item.tokens = lexer.blockTokens(item.text, mutableListOf())
            if (!list.loose) {
                val spacers = item.tokens!!.filter { it.type == TokenType.SPACE }
                list.loose = spacers.isNotEmpty() && spacers.any { MarkedRules.anyLine.test(it.raw) }
            }
        }
        for (item in list.items) placeCheckbox(list, item)
        if (list.loose) {
            for (item in list.items) {
                item.loose = true
                for (token in item.tokens!!) if (token.type == TokenType.TEXT) token.type = TokenType.PARAGRAPH
            }
        }
        return list
    }

    private fun placeCheckbox(list: Token, item: Token) {
        val itemTokens = item.tokens!!
        val first = itemTokens.firstOrNull()
        if (!item.task) return
        if (first?.type != TokenType.TEXT && first?.type != TokenType.PARAGRAPH) {
            item.task = false
            return
        }
        item.text = MarkedRules.listReplaceTask.removeFirst(item.text)
        first.raw = MarkedRules.listReplaceTask.removeFirst(first.raw)
        first.text = MarkedRules.listReplaceTask.removeFirst(first.text)
        for (index in lexer.inlineQueue.indices.reversed()) {
            val job = lexer.inlineQueue[index]
            if (MarkedRules.listIsTask.startsAt(job.src)) {
                job.src = MarkedRules.listReplaceTask.removeFirst(job.src)
                break
            }
        }
        val taskRaw = MarkedRules.listTaskCheckbox.exec(item.raw) ?: return
        val checkbox = Token(TokenType.CHECKBOX, taskRaw.value + MarkedConstants.SPACE).apply { checked = taskRaw.value != MarkedConstants.UNCHECKED_BOX }
        item.checked = checkbox.checked
        if (!list.loose) {
            itemTokens.add(0, checkbox)
            return
        }
        val head = itemTokens.firstOrNull()
        val headTokens = head?.tokens
        if (head != null && (head.type == TokenType.PARAGRAPH || head.type == TokenType.TEXT) && headTokens != null) {
            head.raw = checkbox.raw + head.raw
            head.text = checkbox.raw + head.text
            headTokens.add(0, checkbox)
        } else {
            itemTokens.add(0, Token(TokenType.PARAGRAPH, checkbox.raw, checkbox.raw).apply { tokens = mutableListOf(checkbox) })
        }
    }

    fun html(src: CharSequence): Token? {
        val cap = MarkedRules.html.lookingAt(src) ?: return null
        val raw = trimTrailingBlankLines(cap.value)
        return Token(TokenType.HTML, raw, raw)
    }

    fun def(src: CharSequence): Token? {
        val cap = MarkedRules.def.lookingAt(src) ?: return null
        val destination = cap.group(RegexGroups.SECOND).orEmpty()
        if (!MarkedRules.startAngleBracket.test(destination) && findClosingBracket(destination, MarkedConstants.PARENTHESES) != MarkedConstants.NOT_FOUND) return null
        val href = if (destination.isNotEmpty()) {
            MarkedRules.anyPunctuation.replaceAllWithGroup(MarkedRules.hrefBrackets.replaceFirstMatch(destination) { it.group(1).orEmpty() }, 1)
        } else {
            ""
        }
        val titleText = cap.group(RegexGroups.THIRD)
        val title = if (titleText.truthy()) MarkedRules.anyPunctuation.replaceAllWithGroup(titleText!!.substring(1, titleText.length - 1), 1) else titleText
        return Token(TokenType.DEF, rtrim(cap.value, NEWLINE)).apply {
            tag = normalizeLabel(cap.group(1).orEmpty())
            this.href = href
            this.title = title
        }
    }

    fun table(src: CharSequence): Token? {
        val cap = MarkedRules.table.lookingAt(src) ?: return null
        val delimiter = cap.group(RegexGroups.SECOND).orEmpty()
        if (!MarkedRules.tableDelimiter.test(delimiter)) return null
        val headers = splitCells(cap.group(1).orEmpty())
        val aligns = MarkedRules.tableAlignChars.removeAll(delimiter).split(MarkedConstants.PIPE)
        val cells = cap.group(RegexGroups.THIRD)
        val rows = if (cells != null && jsTrim(cells).isNotEmpty()) MarkedRules.tableRowBlankLine.removeFirst(cells).split(NEWLINE_TEXT) else emptyList()
        if (headers.size != aligns.size) return null
        for (header in headers) lexer.inline(header)
        for (row in rows) for (cell in splitCells(row, headers.size)) lexer.inline(cell)
        return Token(TokenType.TABLE, rtrim(cap.value, NEWLINE))
    }

    fun lheading(src: CharSequence): Token? {
        val cap = MarkedBlockScanner.setextHeading(src) ?: return null
        val text = jsTrim(cap.text)
        return Token(TokenType.HEADING, rtrim(cap.value, NEWLINE), text).apply {
            depth = if (cap.underline.first() == MarkedConstants.EQUALS) 1 else MarkedConstants.PAIR
            tokens = lexer.inline(text)
        }
    }

    fun paragraph(src: CharSequence): Token? {
        val end = MarkedBlockScanner.paragraphEnd(src, 0, MarkedRules.paragraphInterrupt)
        if (end == MarkedConstants.NOT_FOUND) return null
        val body = src.substring(0, end)
        val text = if (body.endsWith(NEWLINE)) body.dropLast(1) else body
        return Token(TokenType.PARAGRAPH, body, text).apply { tokens = lexer.inline(text) }
    }

    fun text(src: CharSequence): Token? {
        val cap = MarkedRules.text.lookingAt(src) ?: return null
        return Token(TokenType.TEXT, cap.value, cap.value).apply { tokens = lexer.inline(cap.value) }
    }

    fun escape(src: CharSequence): Token? {
        val cap = MarkedRules.escape.lookingAt(src) ?: return null
        return Token(TokenType.ESCAPE, cap.value, cap.group(1).orEmpty())
    }

    fun tag(src: CharSequence): Token? {
        val cap = MarkedRules.tag.lookingAt(src) ?: return null
        val value = cap.value
        if (!lexer.inLink && MarkedRules.startATag.test(value)) {
            lexer.inLink = true
        } else if (lexer.inLink && MarkedRules.endATag.test(value)) {
            lexer.inLink = false
        }
        if (!lexer.inRawBlock && MarkedRules.startPreScriptTag.test(value)) {
            lexer.inRawBlock = true
        } else if (lexer.inRawBlock && MarkedRules.endPreScriptTag.test(value)) {
            lexer.inRawBlock = false
        }
        return Token(TokenType.HTML, value, value)
    }

    private fun isLabelEndInsideToken(src: CharSequence, label: String, labelStart: Int): Boolean {
        if (!label.contains(MarkedConstants.LESS_THAN)) return false
        var index = 0
        while (index < label.length) {
            val char = label[index]
            if (char == MarkedConstants.BACKSLASH) {
                index += MarkedConstants.PAIR
                continue
            }
            if (char == MarkedConstants.BACKTICK) {
                val code = MarkedRules.inlineCode.lookingAt(label.substring(index))
                if (code != null) {
                    index += code.value.length
                    continue
                }
            }
            if (char != MarkedConstants.LESS_THAN) {
                index += 1
                continue
            }
            val tokenSrc = src.dropStart(labelStart + index)
            val token = MarkedRules.tag.lookingAt(tokenSrc) ?: MarkedRules.autolink.lookingAt(tokenSrc)
            if (token == null) {
                index += 1
                continue
            }
            if (token.value.length > label.length - index) return true
            index += token.value.length
        }
        return false
    }

    fun link(src: CharSequence): Token? {
        if (!lexer.linkParenPossible) return null
        val cap = MarkedRules.link.lookingAt(src) ?: return null
        var whole = cap.value
        val label = cap.group(1).orEmpty()
        var destination = cap.group(RegexGroups.SECOND).orEmpty()
        var titleText = cap.group(RegexGroups.THIRD)
        val labelStart = if (whole.first() == MarkedConstants.BANG) MarkedConstants.PAIR else 1
        if (isLabelEndInsideToken(src, label, labelStart)) return null
        val trimmedUrl = jsTrim(destination)
        if (MarkedRules.startAngleBracket.test(trimmedUrl)) {
            if (!MarkedRules.endAngleBracket.test(trimmedUrl)) return null
            val rtrimSlash = rtrim(trimmedUrl.dropLast(1), MarkedConstants.BACKSLASH)
            if ((trimmedUrl.length - rtrimSlash.length) % MarkedConstants.PAIR == 0) return null
        } else {
            val lastParenIndex = findClosingBracket(destination, MarkedConstants.PARENTHESES)
            if (lastParenIndex == MarkedConstants.CLOSING_BRACKET_UNBALANCED) return null
            if (lastParenIndex > MarkedConstants.NOT_FOUND) {
                val start = if (whole.indexOf(MarkedConstants.BANG) == 0) MarkedConstants.IMAGE_LINK_PREFIX_LENGTH else MarkedConstants.LINK_PREFIX_LENGTH
                val linkLength = start + label.length + lastParenIndex
                destination = destination.substring(0, lastParenIndex)
                whole = jsTrim(whole.substring(0, minOf(linkLength, whole.length)))
                titleText = ""
            }
        }
        val unescaped = rtrim(whole.dropLast(1), MarkedConstants.BACKSLASH)
        if ((whole.length - unescaped.length) % MarkedConstants.PAIR == 0) return null
        val title = if (titleText.truthy()) jsSlice(titleText!!, 1, -1) else ""
        var href = jsTrim(destination)
        if (MarkedRules.startAngleBracket.test(href)) href = jsSlice(href, 1, -1)
        return outputLink(
            whole,
            label,
            if (href.isNotEmpty()) MarkedRules.anyPunctuation.replaceAllWithGroup(href, 1) else href,
            if (title.isNotEmpty()) MarkedRules.anyPunctuation.replaceAllWithGroup(title, 1) else title,
        )
    }

    fun reflink(src: CharSequence, links: Map<String, LinkDefinition>): Token? {
        val cap = MarkedRules.reflink.lookingAt(src) ?: MarkedRules.nolink.lookingAt(src) ?: return null
        val labelStart = if (cap.value.first() == MarkedConstants.BANG) MarkedConstants.PAIR else 1
        val label = cap.group(1).orEmpty()
        if (isLabelEndInsideToken(src, label, labelStart)) return null
        val reference = cap.group(RegexGroups.SECOND)
        val link = links[normalizeLabel(if (reference.truthy()) reference!! else label)]
        if (link == null) {
            val text = cap.value.substring(0, 1)
            return Token(TokenType.TEXT, text, text)
        }
        return outputLink(cap.value, label, link.href, link.title.orEmpty())
    }

    private fun outputLink(raw: String, label: String, href: String, title: String): Token? {
        val text = MarkedRules.outputLinkReplace.replaceAllWithGroup(label, 1)
        val isImage = raw.first() == MarkedConstants.BANG
        val outerInLink = lexer.inLink
        val outerLinkEmitted = lexer.linkEmitted
        val outerInRawBlock = lexer.inRawBlock
        lexer.inLink = true
        lexer.linkEmitted = false
        val tokens = lexer.inlineTokens(text)
        val textHasLink = lexer.linkEmitted
        lexer.linkEmitted = outerLinkEmitted
        lexer.inLink = outerInLink
        if (!isImage) {
            if (textHasLink) {
                lexer.inRawBlock = outerInRawBlock
                return null
            }
            lexer.linkEmitted = true
        }
        return Token(if (isImage) TokenType.IMAGE else TokenType.LINK, raw, text).apply {
            this.href = href
            this.title = title.ifEmpty { null }
            this.tokens = tokens
        }
    }

    fun emStrong(src: CharSequence, maskedSource: String, prevCharArg: String, hints: CloserHints): Token? {
        val opener = MarkedRules.emStrongLDelim.lookingAt(src) ?: return null
        if (!opener.group(1).truthy() && !opener.group(RegexGroups.SECOND).truthy() && !opener.group(RegexGroups.THIRD).truthy() && !opener.group(RegexGroups.FOURTH).truthy()) return null
        var prevChar = prevCharArg
        val delimChar = opener.value[0]
        val midRun = prevChar == delimChar.toString()
        val srcStart = maskedSource.length - src.length
        var runStart = srcStart
        while (runStart > 0 && maskedSource[runStart - 1] == delimChar) runStart -= 1
        val runPrevChar = codePointsOf(maskedSource.substring(maxOf(0, runStart - MarkedConstants.PAIR), runStart)).lastOrNull() ?: ""
        val runPrevPunct = runPrevChar == MarkedConstants.ASTERISK.toString() || runPrevChar == MarkedConstants.UNDERSCORE.toString() ||
            MarkedRules.punctuation.exec(runPrevChar) != null
        if (runStart < srcStart) prevChar = runPrevChar
        val prevPunct = if (runStart < srcStart) runPrevPunct else MarkedRules.punctuation.exec(prevChar) != null
        if (opener.group(RegexGroups.FOURTH).truthy() && MarkedRules.unicodeAlphaNumeric.test(prevChar)) return null
        val nextChar = opener.group(1).takeIf { it.truthy() } ?: opener.group(RegexGroups.THIRD).takeIf { it.truthy() } ?: ""
        if (nextChar.isNotEmpty() && prevChar.isNotEmpty() && !prevPunct) return null
        val lLength = codePointLength(opener.value) - 1
        var delimTotal = lLength
        var midDelimTotal = 0
        val runLength = srcStart - runStart + lLength
        val openerCanClose = jsTrim(runPrevChar).isNotEmpty() && (nextChar.isNotEmpty() || (delimChar == MarkedConstants.ASTERISK && !runPrevPunct))
        if (delimChar != MarkedConstants.ASTERISK && delimChar != MarkedConstants.UNDERSCORE) return null
        val endReg = if (delimChar == MarkedConstants.ASTERISK) MarkedRules.emStrongRDelimAst else MarkedRules.emStrongRDelimUnd
        val lastPossible = hints.emStrong(delimChar)
        val windowStart = maskedSource.length - src.length + lLength
        if (lastPossible < windowStart) return null
        val masked = TextSlice.of(maskedSource).dropStart(maxOf(maskedSource.length - (src.length - lLength), 0))
        val lastInWindow = lastPossible - windowStart
        var from = 0
        while (true) {
            val match = endReg.find(masked, from) ?: break
            from = match.range.last + 1
            if (match.range.first > lastInWindow) break
            val rDelim = (1..MarkedConstants.DELIMITER_GROUPS).firstNotNullOfOrNull { match.group(it).takeIf { group -> group.truthy() } } ?: continue
            var rLength = codePointLength(rDelim)
            if (match.group(MarkedConstants.LEFT_ONLY_GROUP).truthy() || match.group(MarkedConstants.LEFT_ONLY_PUNCT_GROUP).truthy()) {
                delimTotal += rLength
                continue
            } else if (match.group(MarkedConstants.EITHER_PUNCT_GROUP).truthy() || match.group(MarkedConstants.EITHER_GROUP).truthy() || openerCanClose) {
                val ambiguous = match.group(MarkedConstants.EITHER_PUNCT_GROUP).truthy() || match.group(MarkedConstants.EITHER_GROUP).truthy()
                if (midRun && ambiguous) break
                if (runLength % MarkedConstants.EMPHASIS_RULE_MODULUS != 0 && (runLength + rLength) % MarkedConstants.EMPHASIS_RULE_MODULUS == 0) {
                    midDelimTotal += rLength
                    continue
                }
            }
            delimTotal -= rLength
            if (delimTotal > 0) continue
            rLength = minOf(rLength, rLength + delimTotal + midDelimTotal)
            val lastCharLength = codePointsOf(match.value)[0].length
            val raw = src.substring(0, minOf(lLength + match.range.first + lastCharLength + rLength, src.length))
            if (minOf(lLength, rLength) % MarkedConstants.PAIR != 0) {
                val text = jsSlice(raw, 1, -1)
                return Token(TokenType.EM, raw, text).apply { tokens = lexer.inlineTokens(text) }
            }
            val text = jsSlice(raw, MarkedConstants.STRONG_MARKER_LENGTH, -MarkedConstants.STRONG_MARKER_LENGTH)
            return Token(TokenType.STRONG, raw, text).apply { tokens = lexer.inlineTokens(text) }
        }
        return null
    }

    fun codespan(src: CharSequence): Token? {
        val cap = MarkedRules.inlineCode.lookingAt(src) ?: return null
        var text = cap.group(RegexGroups.SECOND).orEmpty().replace(NEWLINE, MarkedConstants.SPACE)
        val hasNonSpaceChars = MarkedRules.nonSpaceChar.test(text)
        val hasSpaceCharsOnBothEnds = MarkedRules.startingSpaceChar.test(text) && MarkedRules.endingSpaceChar.test(text)
        if (hasNonSpaceChars && hasSpaceCharsOnBothEnds) text = text.substring(1, text.length - 1)
        return Token(TokenType.CODESPAN, cap.value, text)
    }

    fun br(src: CharSequence): Token? {
        val cap = MarkedRules.br.lookingAt(src) ?: return null
        return Token(TokenType.BR, cap.value)
    }

    fun del(src: CharSequence, maskedSource: String, prevChar: String, hints: CloserHints): Token? {
        val opener = MarkedRules.delLDelim.lookingAt(src) ?: return null
        val nextChar = opener.group(1).orEmpty()
        if (nextChar.isNotEmpty() && prevChar.isNotEmpty() && MarkedRules.punctuation.exec(prevChar) == null) return null
        val lLength = codePointLength(opener.value) - 1
        var delimTotal = lLength
        val lastPossible = hints.lastTilde
        val windowStart = maskedSource.length - src.length + lLength
        if (lastPossible < windowStart) return null
        val masked = TextSlice.of(maskedSource).dropStart(maxOf(maskedSource.length - (src.length - lLength), 0))
        val lastInWindow = lastPossible - windowStart
        var from = 0
        while (true) {
            val match = MarkedRules.delRDelim.find(masked, from) ?: break
            from = match.range.last + 1
            if (match.range.first > lastInWindow) break
            val rDelim = (1..MarkedConstants.DELIMITER_GROUPS).firstNotNullOfOrNull { match.group(it).takeIf { group -> group.truthy() } } ?: continue
            var rLength = codePointLength(rDelim)
            if (rLength != lLength) continue
            if (match.group(MarkedConstants.LEFT_ONLY_GROUP).truthy() || match.group(MarkedConstants.LEFT_ONLY_PUNCT_GROUP).truthy()) {
                delimTotal += rLength
                continue
            }
            delimTotal -= rLength
            if (delimTotal > 0) continue
            rLength = minOf(rLength, rLength + delimTotal)
            val lastCharLength = codePointsOf(match.value)[0].length
            val raw = src.substring(0, minOf(lLength + match.range.first + lastCharLength + rLength, src.length))
            val text = jsSlice(raw, lLength, -lLength)
            return Token(TokenType.DEL, raw, text).apply { tokens = lexer.inlineTokens(text) }
        }
        return null
    }

    fun autolink(src: CharSequence): Token? {
        val cap = MarkedRules.autolink.lookingAt(src) ?: return null
        val text = cap.group(1).orEmpty()
        val href = if (cap.group(RegexGroups.SECOND) == MarkedConstants.AT) MarkedConstants.MAILTO + text else text
        return Token(TokenType.LINK, cap.value, text).apply {
            this.href = href
            tokens = mutableListOf(Token(TokenType.TEXT, text, text))
        }
    }

    fun url(src: CharSequence): Token? {
        val cap = MarkedRules.url.lookingAt(src) ?: return null
        val text: String
        val href: String
        var raw = cap.value
        if (cap.group(RegexGroups.SECOND) == MarkedConstants.AT) {
            text = raw
            href = MarkedConstants.MAILTO + text
        } else {
            do {
                val previous = raw
                raw = MarkedRules.backpedal.exec(raw)?.value ?: ""
            } while (previous != raw)
            text = raw
            href = if (cap.group(1) == MarkedConstants.WWW) MarkedConstants.HTTP + raw else raw
        }
        return Token(TokenType.LINK, raw, text).apply {
            this.href = href
            tokens = mutableListOf(Token(TokenType.TEXT, text, text))
        }
    }

    fun inlineText(src: CharSequence): Token? {
        val cap = MarkedRules.inlineText.lookingAt(src) ?: return null
        return Token(TokenType.TEXT, cap.value, if (lexer.inRawBlock) cap.value else decodeNumericCharacterReferences(cap.value))
    }
}
