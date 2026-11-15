package dev.kodakiko.domain

import dev.kodakiko.crypto.passwordToBytes
import dev.kodakiko.media.isJsWhitespace

internal fun jsParseInt(text: String): Double {
    var index = 0
    while (index < text.length && isJsWhitespace(text[index])) index += 1
    var sign = 1.0
    if (index < text.length && (text[index] == JsNumberText.PLUS || text[index] == JsNumberText.MINUS)) {
        if (text[index] == JsNumberText.MINUS) sign = -1.0
        index += 1
    }
    val start = index
    var value = 0.0
    while (index < text.length && text[index] in '0'..'9') {
        value = value * JsNumberText.DECIMAL_RADIX + (text[index] - '0')
        index += 1
    }
    return if (index == start) Double.NaN else sign * value
}

internal fun jsIntegerString(value: Double): String = when {
    value.isNaN() -> JsNumberText.NAN
    value == Double.POSITIVE_INFINITY -> JsNumberText.INFINITY
    value == Double.NEGATIVE_INFINITY -> JsNumberText.NEGATIVE_INFINITY
    else -> value.toLong().toString()
}

private fun normalizeWellFormed(segment: String): String =
    if (segment.isEmpty()) segment else passwordToBytes(segment).decodeToString()

internal fun normalizeNfkc(text: String): String {
    if (text.none { it.isSurrogate() }) return normalizeWellFormed(text)
    val normalized = StringBuilder(text.length)
    val segment = StringBuilder()
    var index = 0
    while (index < text.length) {
        val isPair = text[index].isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true
        val length = if (isPair) SURROGATE_PAIR_LENGTH else 1
        val codePoint = text.substring(index, index + length)
        if (!isPair && text[index].isSurrogate()) {
            normalized.append(normalizeWellFormed(segment.toString())).append(codePoint)
            segment.clear()
        } else {
            segment.append(codePoint)
        }
        index += length
    }
    return normalized.append(normalizeWellFormed(segment.toString())).toString()
}
