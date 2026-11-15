package dev.kodakiko.data.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class JsJsonSyntaxError(position: Int) : IllegalArgumentException(DbText.jsonSyntax(position))

internal object JsJson {
    fun parseBytes(bytes: ByteArray): JsonElement = parse(decodeUtf8(bytes).removePrefix(JsJsonConstants.BYTE_ORDER_MARK.toString()))

    fun stringifyBytes(element: JsonElement): ByteArray = stringify(element).encodeToByteArray()

    fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    fun parse(text: String): JsonElement = Parser(text).parseDocument()

    fun stringify(element: JsonElement): String = StringBuilder().also { append(it, element) }.toString()

    private fun append(out: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonNull -> out.append(JsJsonConstants.LITERAL_NULL)
            is JsonObject -> {
                out.append('{')
                element.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    quote(out, key)
                    out.append(':')
                    append(out, value)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(',')
                    append(out, value)
                }
                out.append(']')
            }
            is JsonPrimitive -> if (element.isString) quote(out, element.content) else out.append(element.content)
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        text.forEachIndexed { index, char ->
            val short = JsJsonConstants.SHORT_QUOTES[char]
            when {
                short != null -> out.append(short)
                char.code < JsJsonConstants.FIRST_NON_CONTROL || isLoneSurrogate(text, index) -> unicodeEscape(out, char)
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private fun isLoneSurrogate(text: String, index: Int): Boolean {
        val char = text[index]
        return when {
            char.isHighSurrogate() -> index + 1 >= text.length || !text[index + 1].isLowSurrogate()
            char.isLowSurrogate() -> index == 0 || !text[index - 1].isHighSurrogate()
            else -> false
        }
    }

    private fun unicodeEscape(out: StringBuilder, char: Char) {
        out.append(JsJsonConstants.UNICODE_ESCAPE_PREFIX)
        for (digit in JsJsonConstants.UNICODE_ESCAPE_DIGITS - 1 downTo 0) {
            out.append(JsJsonConstants.HEX_DIGITS[(char.code shr (digit * JsJsonConstants.NIBBLE_BITS)) and JsJsonConstants.NIBBLE_MASK])
        }
    }

    private sealed interface Frame {
        fun add(value: JsonElement)

        fun build(): JsonElement

        val close: Char
    }

    private class ArrayFrame : Frame {
        private val items = mutableListOf<JsonElement>()

        override val close = ']'

        override fun add(value: JsonElement) {
            items += value
        }

        override fun build(): JsonElement = JsonArray(items)
    }

    private class ObjectFrame : Frame {
        private val members = LinkedHashMap<String, JsonElement>()
        var key: String = ""

        override val close = '}'

        override fun add(value: JsonElement) {
            members[key] = value
        }

        override fun build(): JsonElement = JsonObject(members)
    }

    private class Parser(private val text: String) {
        private var position = 0
        private val stack = ArrayDeque<Frame>()

        fun parseDocument(): JsonElement {
            var result: JsonElement? = null
            while (result == null) {
                val value = readValueOrOpen() ?: continue
                result = attach(value)
            }
            skipWhitespace()
            if (position != text.length) fail()
            return result
        }

        private fun attach(value: JsonElement): JsonElement? {
            var current = value
            while (true) {
                val frame = stack.lastOrNull() ?: return current
                frame.add(current)
                skipWhitespace()
                val next = peek()
                when {
                    next == ',' -> {
                        position++
                        if (frame is ObjectFrame) frame.key = readKey()
                        return null
                    }
                    next == frame.close -> {
                        position++
                        stack.removeLast()
                        current = frame.build()
                    }
                    else -> fail()
                }
            }
        }

        private fun readValueOrOpen(): JsonElement? {
            skipWhitespace()
            return when (val char = peek()) {
                '{' -> open(ObjectFrame(), '}')
                '[' -> open(ArrayFrame(), ']')
                '"' -> JsonPrimitive(readString())
                't' -> literal(JsJsonConstants.LITERAL_TRUE, JsonPrimitive(true))
                'f' -> literal(JsJsonConstants.LITERAL_FALSE, JsonPrimitive(false))
                'n' -> literal(JsJsonConstants.LITERAL_NULL, JsonNull)
                else -> if (char == '-' || char?.isAsciiDigit() == true) readNumber() else fail()
            }
        }

        private fun open(frame: Frame, close: Char): JsonElement? {
            position++
            skipWhitespace()
            if (peek() == close) {
                position++
                return frame.build()
            }
            stack.addLast(frame)
            if (frame is ObjectFrame) frame.key = readKeyAt()
            return null
        }

        private fun readKey(): String {
            skipWhitespace()
            return readKeyAt()
        }

        private fun readKeyAt(): String {
            if (peek() != '"') fail()
            val key = readString()
            skipWhitespace()
            if (peek() != ':') fail()
            position++
            return key
        }

        private fun literal(word: String, value: JsonElement): JsonElement {
            if (!text.startsWith(word, position)) fail()
            position += word.length
            return value
        }

        private fun readNumber(): JsonElement {
            val match = JsJsonConstants.NUMBER_PREFIX.matchAt(text, position) ?: fail()
            position = match.range.last + 1
            return JsonPrimitive(match.value.toDouble())
        }

        private fun readString(): String {
            position++
            val out = StringBuilder()
            while (true) {
                val char = peek() ?: fail()
                position++
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> out.append(readEscape())
                    char.code < JsJsonConstants.FIRST_NON_CONTROL -> fail()
                    else -> out.append(char)
                }
            }
        }

        private fun readEscape(): Char {
            val char = peek() ?: fail()
            position++
            JsJsonConstants.SHORT_ESCAPES[char]?.let { return it }
            if (char != 'u') fail()
            val end = position + JsJsonConstants.UNICODE_ESCAPE_DIGITS
            if (end > text.length) fail()
            val digits = text.substring(position, end)
            if (!digits.all { it.isAsciiHexDigit() }) fail()
            position = end
            return digits.toInt(JsJsonConstants.HEX_RADIX).toChar()
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position] in JsJsonConstants.WHITESPACE) position++
        }

        private fun peek(): Char? = text.getOrNull(position)

        private fun fail(): Nothing = throw JsJsonSyntaxError(position)

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        private fun Char.isAsciiHexDigit(): Boolean = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'
    }
}
