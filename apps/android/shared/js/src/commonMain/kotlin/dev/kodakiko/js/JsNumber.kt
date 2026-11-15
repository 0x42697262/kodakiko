package dev.kodakiko.js

import dev.kodakiko.js.JsNumberConstants.DECIMAL_POINT
import dev.kodakiko.js.JsNumberConstants.KOTLIN_EXPONENT_MARKER
import dev.kodakiko.js.JsNumberConstants.MINUS
import dev.kodakiko.js.JsNumberConstants.PLUS
import dev.kodakiko.js.JsNumberConstants.RADIX_PREFIX_LENGTH
import dev.kodakiko.js.JsNumberConstants.ZERO
import kotlin.math.abs
import kotlin.math.floor

fun jsParseInt(text: String): Double {
    var index = 0
    while (index < text.length && isJsWhitespace(text[index])) index += 1
    var sign = 1.0
    if (index < text.length && (text[index] == PLUS || text[index] == MINUS)) {
        if (text[index] == MINUS) sign = -1.0
        index += 1
    }
    val start = index
    var value = 0.0
    while (index < text.length && isAsciiDigit(text[index])) {
        value = value * JsNumberConstants.DECIMAL_RADIX + (text[index] - ZERO)
        index += 1
    }
    return if (index == start) Double.NaN else sign * value
}

fun isAsciiDigit(char: Char): Boolean = char in ZERO..JsNumberConstants.DIGIT_NINE

fun jsIntegerString(value: Double): String = when {
    value.isNaN() -> JsNumberConstants.NAN
    value == Double.POSITIVE_INFINITY -> JsNumberConstants.INFINITY
    value == Double.NEGATIVE_INFINITY -> JsNumberConstants.NEGATIVE_INFINITY
    else -> value.toLong().toString()
}

fun jsRound(value: Double): Double {
    val lower = floor(value)
    return if (value - lower >= JsNumberConstants.ROUND_HALF) lower + 1 else lower
}

fun jsStringToNumber(text: String): Double {
    val trimmed = jsTrim(text)
    if (trimmed.isEmpty()) return 0.0
    val prefix = trimmed.take(RADIX_PREFIX_LENGTH).lowercase()
    val radix = when (prefix) {
        JsNumberConstants.HEX_PREFIX -> JsNumberConstants.HEX_RADIX
        JsNumberConstants.OCTAL_PREFIX -> JsNumberConstants.OCTAL_RADIX
        JsNumberConstants.BINARY_PREFIX -> JsNumberConstants.BINARY_RADIX
        else -> null
    }
    return when {
        radix != null -> parseRadixDigits(trimmed.drop(RADIX_PREFIX_LENGTH), radix)
        JsNumberConstants.INFINITY_PATTERN.matches(trimmed) ->
            if (trimmed.first() == MINUS) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        JsNumberConstants.DECIMAL.matches(trimmed) -> trimmed.toDouble()
        else -> Double.NaN
    }
}

private fun parseRadixDigits(digits: String, radix: Int): Double {
    if (digits.isEmpty()) return Double.NaN
    var value = 0.0
    for (char in digits) {
        val digit = char.digitToIntOrNull(radix) ?: return Double.NaN
        value = value * radix + digit
    }
    return value
}

fun jsonStringifyNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return JsNumberConstants.JSON_NULL
    if (value == 0.0) return ZERO.toString()
    if (value < 0) return MINUS + jsonStringifyNumber(-value)
    val (digits, pointPosition) = shortestDigits(value)
    return formatJsNumber(digits, pointPosition)
}

private fun decimalParts(value: Double): Pair<String, Int> {
    val text = value.toString()
    val mantissa = text.substringBefore(KOTLIN_EXPONENT_MARKER)
    val exponent = text.substringAfter(KOTLIN_EXPONENT_MARKER, "").ifEmpty { null }?.toInt() ?: 0
    val integerPart = mantissa.substringBefore(DECIMAL_POINT)
    val fractionPart = mantissa.substringAfter(DECIMAL_POINT, "")
    val allDigits = integerPart + fractionPart
    val leadingZeros = allDigits.indexOfFirst { it != ZERO }
    val digits = allDigits.substring(leadingZeros).trimEnd(ZERO)
    return digits to integerPart.length + exponent - leadingZeros
}

private fun shortestDigits(value: Double): Pair<String, Int> {
    val (digits, pointPosition) = decimalParts(value)
    for (length in 1 until digits.length) {
        val (rounded, roundedPosition) = roundDigits(digits, pointPosition, length)
        if (parseDigits(rounded, roundedPosition) == value) return rounded to roundedPosition
    }
    return digits to pointPosition
}

private fun roundDigits(digits: String, pointPosition: Int, length: Int): Pair<String, Int> {
    val kept = digits.take(length).toCharArray()
    if (digits[length] < JsNumberConstants.ROUND_UP_DIGIT) return kept.concatToString().trimEnd(ZERO) to pointPosition
    var index = length - 1
    while (index >= 0 && kept[index] == JsNumberConstants.DIGIT_NINE) {
        kept[index] = ZERO
        index -= 1
    }
    if (index < 0) return JsNumberConstants.ONE_DIGIT to pointPosition + 1
    kept[index] = kept[index] + 1
    return kept.concatToString().trimEnd(ZERO) to pointPosition
}

private fun parseDigits(digits: String, pointPosition: Int): Double =
    (ZERO + DECIMAL_POINT.toString() + digits + KOTLIN_EXPONENT_MARKER + pointPosition).toDouble()

private fun formatJsNumber(digits: String, pointPosition: Int): String {
    val count = digits.length
    return when {
        pointPosition in count..JsNumberConstants.FIXED_EXPONENT_MAX -> digits + ZERO.toString().repeat(pointPosition - count)
        pointPosition in 1..JsNumberConstants.FIXED_EXPONENT_MAX -> digits.substring(0, pointPosition) + DECIMAL_POINT + digits.substring(pointPosition)
        pointPosition > JsNumberConstants.FIXED_EXPONENT_MIN && pointPosition <= 0 ->
            ZERO.toString() + DECIMAL_POINT + ZERO.toString().repeat(-pointPosition) + digits
        else -> {
            val exponent = pointPosition - 1
            val sign = if (exponent < 0) MINUS else PLUS
            val mantissa = if (count == 1) digits else digits.take(1) + DECIMAL_POINT + digits.drop(1)
            mantissa + JsNumberConstants.JS_EXPONENT_MARKER + sign + abs(exponent)
        }
    }
}
