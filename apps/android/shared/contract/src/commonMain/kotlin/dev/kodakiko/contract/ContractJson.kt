package dev.kodakiko.contract

import dev.kodakiko.js.jsonStringifyNumber
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ContractJson {
    private const val QUOTE = '"'
    private const val BACKSLASH = '\\'
    private const val COMMA = ','
    private const val COLON = ':'
    private const val ARRAY_OPEN = '['
    private const val ARRAY_CLOSE = ']'
    private const val OBJECT_OPEN = '{'
    private const val OBJECT_CLOSE = '}'
    private const val UNICODE_ESCAPE = "\\u"
    private val SHORT_ESCAPES = mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\b' to "\\b",
        '\u000C' to "\\f",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t",
    )

    fun stringify(element: JsonElement): String = StringBuilder().also { write(it, element) }.toString()

    fun parse(text: String): JsonElement {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw ContractViolation(ContractText.JSON_SCHEMA, ContractText.NOT_JSON)
        } catch (error: IllegalArgumentException) {
            throw ContractViolation(ContractText.JSON_SCHEMA, ContractText.NOT_JSON)
        }
        requireStrict(element)
        return element
    }

    private fun requireStrict(element: JsonElement) {
        when (element) {
            is JsonNull -> Unit
            is JsonPrimitive -> if (!element.isString && !isLiteral(element)) throw ContractViolation(ContractText.JSON_SCHEMA, ContractText.NOT_JSON)
            is JsonArray -> element.forEach { requireStrict(it) }
            is JsonObject -> element.values.forEach { requireStrict(it) }
        }
    }

    private fun isLiteral(primitive: JsonPrimitive): Boolean =
        primitive.content == ContractPatterns.JSON_TRUE || primitive.content == ContractPatterns.JSON_FALSE || isJsonNumber(primitive)

    private fun write(builder: StringBuilder, element: JsonElement) {
        when (element) {
            is JsonNull -> builder.append(ContractPatterns.JSON_NULL)
            is JsonPrimitive -> writePrimitive(builder, element)
            is JsonArray -> {
                builder.append(ARRAY_OPEN)
                element.forEachIndexed { index, item ->
                    if (index > 0) builder.append(COMMA)
                    write(builder, item)
                }
                builder.append(ARRAY_CLOSE)
            }
            is JsonObject -> {
                builder.append(OBJECT_OPEN)
                element.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) builder.append(COMMA)
                    writeString(builder, key)
                    builder.append(COLON)
                    write(builder, value)
                }
                builder.append(OBJECT_CLOSE)
            }
        }
    }

    private fun writePrimitive(builder: StringBuilder, primitive: JsonPrimitive) {
        val content = primitive.content
        when {
            primitive.isString -> writeString(builder, content)
            content == ContractPatterns.JSON_TRUE || content == ContractPatterns.JSON_FALSE -> builder.append(content)
            else -> {
                val number = content.toDoubleOrNull()
                    ?: throw ContractViolation(ContractText.JSON_SCHEMA, ContractText.at(content, ContractText.UNPRINTABLE_NUMBER))
                builder.append(jsonStringifyNumber(number))
            }
        }
    }

    private fun writeString(builder: StringBuilder, text: String) {
        builder.append(QUOTE)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val short = SHORT_ESCAPES[char]
            when {
                short != null -> builder.append(short)
                char < JSON_CONTROL_CHAR_LIMIT -> appendUnicodeEscape(builder, char)
                char.isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                    builder.append(char).append(text[index + 1])
                    index += 1
                }
                char.isSurrogate() -> appendUnicodeEscape(builder, char)
                else -> builder.append(char)
            }
            index += 1
        }
        builder.append(QUOTE)
    }

    private fun appendUnicodeEscape(builder: StringBuilder, char: Char) {
        builder.append(UNICODE_ESCAPE).append(char.code.toString(HEX_RADIX).padStart(JSON_UNICODE_ESCAPE_DIGITS, ContractPatterns.ZERO))
    }
}
