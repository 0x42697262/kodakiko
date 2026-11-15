package dev.kodakiko.tooling

data class Comment(val line: Int, val text: String)

data class NumberLiteral(val line: Int, val text: String)

data class StringLiteral(val line: Int, val start: Int, val end: Int, val nested: Boolean)

data class Lexed(val comments: List<Comment>, val numbers: List<NumberLiteral>, val strings: List<StringLiteral> = emptyList())

object KotlinLexer {
    private enum class Mode { CODE, TEMPLATE, STRING, RAW_STRING }

    private class Frame(val mode: Mode, var braces: Int = 0, val start: Int = 0, val startLine: Int = 0, val nested: Boolean = false)

    fun lex(text: String): Lexed {
        val comments = mutableListOf<Comment>()
        val numbers = mutableListOf<NumberLiteral>()
        val strings = mutableListOf<StringLiteral>()
        val stack = ArrayDeque<Frame>().apply { addLast(Frame(Mode.CODE)) }
        var index = 0
        var line = 1

        fun at(offset: Int): Char = if (index + offset < text.length) text[index + offset] else '\u0000'

        fun advance(count: Int = 1) {
            repeat(count) {
                if (index < text.length && text[index] == '\n') line++
                index++
            }
        }

        while (index < text.length) {
            val frame = stack.last()
            val c = at(0)
            when (frame.mode) {
                Mode.CODE, Mode.TEMPLATE -> when {
                    c == '/' && at(1) == '/' -> {
                        val start = index
                        val startLine = line
                        while (index < text.length && at(0) != '\n') advance()
                        comments += Comment(startLine, text.substring(start, index))
                    }
                    c == '/' && at(1) == '*' -> {
                        val start = index
                        val startLine = line
                        var depth = 0
                        do {
                            if (at(0) == '/' && at(1) == '*') {
                                depth++
                                advance(2)
                            } else if (at(0) == '*' && at(1) == '/') {
                                depth--
                                advance(2)
                            } else {
                                advance()
                            }
                        } while (depth > 0 && index < text.length)
                        comments += Comment(startLine, text.substring(start, index))
                    }
                    c == '"' && at(1) == '"' && at(2) == '"' -> {
                        stack.addLast(Frame(Mode.RAW_STRING, start = index, startLine = line, nested = stack.size > 1))
                        advance(3)
                    }
                    c == '"' -> {
                        stack.addLast(Frame(Mode.STRING, start = index, startLine = line, nested = stack.size > 1))
                        advance()
                    }
                    c == '\'' -> {
                        advance()
                        while (index < text.length && at(0) != '\'') {
                            if (at(0) == '\\') advance()
                            advance()
                        }
                        advance()
                    }
                    c == '`' -> {
                        advance()
                        while (index < text.length && at(0) != '`') advance()
                        advance()
                    }
                    c == '{' -> {
                        frame.braces++
                        advance()
                    }
                    c == '}' -> {
                        if (frame.mode == Mode.TEMPLATE && frame.braces == 0) {
                            stack.removeLast()
                        } else {
                            frame.braces--
                        }
                        advance()
                    }
                    c.isLetter() || c == '_' -> {
                        while (index < text.length && (at(0).isLetterOrDigit() || at(0) == '_')) advance()
                    }
                    c.isDigit() -> {
                        val start = index
                        val startLine = line
                        advance()
                        while (index < text.length) {
                            val d = at(0)
                            val exponentSign = (d == '+' || d == '-') && (at(-1) == 'e' || at(-1) == 'E') &&
                                !text.substring(start, index).startsWith("0x", ignoreCase = true)
                            val decimalPoint = d == '.' && at(1).isDigit()
                            if (d.isLetterOrDigit() || d == '_' || decimalPoint || exponentSign) advance() else break
                        }
                        numbers += NumberLiteral(startLine, text.substring(start, index))
                    }
                    else -> advance()
                }
                Mode.STRING -> when {
                    c == '\\' -> advance(2)
                    c == '$' && at(1) == '{' -> {
                        advance(2)
                        stack.addLast(Frame(Mode.TEMPLATE))
                    }
                    c == '"' -> {
                        advance()
                        stack.removeLast()
                        strings += StringLiteral(frame.startLine, frame.start, index, frame.nested)
                    }
                    else -> advance()
                }
                Mode.RAW_STRING -> when {
                    c == '$' && at(1) == '{' -> {
                        advance(2)
                        stack.addLast(Frame(Mode.TEMPLATE))
                    }
                    c == '"' && at(1) == '"' && at(2) == '"' -> {
                        advance(3)
                        while (at(0) == '"') advance()
                        stack.removeLast()
                        strings += StringLiteral(frame.startLine, frame.start, index, frame.nested)
                    }
                    else -> advance()
                }
            }
        }
        return Lexed(comments, numbers, strings)
    }

    fun numericValueIsTrivial(literal: String): Boolean {
        val cleaned = literal.replace("_", "").lowercase().removeSuffix("ul").removeSuffix("l").removeSuffix("u")
        val value = when {
            cleaned.startsWith("0x") -> cleaned.removePrefix("0x").toBigIntegerOrNull(16)?.toBigDecimal()
            cleaned.startsWith("0b") -> cleaned.removePrefix("0b").toBigIntegerOrNull(2)?.toBigDecimal()
            else -> cleaned.removeSuffix("f").toBigDecimalOrNull()
        } ?: return false
        return value.compareTo(java.math.BigDecimal.ZERO) == 0 || value.compareTo(java.math.BigDecimal.ONE) == 0
    }
}
