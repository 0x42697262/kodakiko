package dev.kodakiko.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

internal fun jsTrim(text: String): String = text.trim { it in JS_WHITESPACE }

internal fun jsToNumber(json: JsonElement): Double =
    when (json) {
        is JsonNull -> 0.0
        is JsonPrimitive -> primitiveToNumber(json)
        is JsonArray -> jsStringToNumber(jsArrayToString(json))
        is JsonObject -> Double.NaN
    }

private fun primitiveToNumber(primitive: JsonPrimitive): Double =
    when {
        primitive.isString -> jsStringToNumber(primitive.content)
        primitive.content == ContractPatterns.JSON_TRUE -> 1.0
        primitive.content == ContractPatterns.JSON_FALSE -> 0.0
        isJsonNumber(primitive) -> primitive.content.toDouble()
        else -> Double.NaN
    }

private fun jsElementToString(json: JsonElement): String =
    when (json) {
        is JsonNull -> ""
        is JsonPrimitive -> if (isJsonNumber(json)) jsNumberToString(json.content.toDouble()) else json.content
        is JsonArray -> jsArrayToString(json)
        is JsonObject -> ContractPatterns.JS_OBJECT_STRING
    }

private fun jsArrayToString(array: JsonArray): String = array.joinToString(ContractPatterns.JS_ARRAY_SEPARATOR) { jsElementToString(it) }

internal fun jsStringToNumber(text: String): Double {
    val trimmed = jsTrim(text)
    if (trimmed.isEmpty()) return 0.0
    val prefix = trimmed.take(RADIX_PREFIX_LENGTH).lowercase()
    val radix = when (prefix) {
        ContractPatterns.HEX_PREFIX -> HEX_RADIX
        ContractPatterns.OCTAL_PREFIX -> OCTAL_RADIX
        ContractPatterns.BINARY_PREFIX -> BINARY_RADIX
        else -> null
    }
    return when {
        radix != null -> parseRadixDigits(trimmed.drop(RADIX_PREFIX_LENGTH), radix)
        ContractPatterns.JS_INFINITY.matches(trimmed) ->
            if (trimmed.first() == ContractPatterns.NEGATIVE_INFINITY_PREFIX) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        ContractPatterns.JS_DECIMAL.matches(trimmed) -> trimmed.toDouble()
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

internal fun jsNumberToString(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return ContractPatterns.JSON_NULL
    if (value == 0.0) return ContractPatterns.ZERO.toString()
    if (value < 0) return ContractPatterns.MINUS + jsNumberToString(-value)
    val (digits, pointPosition) = shortestDigits(value)
    return formatJsNumber(digits, pointPosition)
}

private fun decimalParts(value: Double): Pair<String, Int> {
    val text = value.toString()
    val mantissa = text.substringBefore(ContractPatterns.KOTLIN_EXPONENT_MARKER)
    val exponent = text.substringAfter(ContractPatterns.KOTLIN_EXPONENT_MARKER, "").ifEmpty { null }?.toInt() ?: 0
    val integerPart = mantissa.substringBefore(ContractPatterns.DECIMAL_POINT)
    val fractionPart = mantissa.substringAfter(ContractPatterns.DECIMAL_POINT, "")
    val allDigits = integerPart + fractionPart
    val leadingZeros = allDigits.indexOfFirst { it != ContractPatterns.ZERO }
    val digits = allDigits.substring(leadingZeros).trimEnd(ContractPatterns.ZERO)
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
    if (digits[length] < JS_ROUND_UP_DIGIT) return kept.concatToString().trimEnd(ContractPatterns.ZERO) to pointPosition
    var index = length - 1
    while (index >= 0 && kept[index] == JS_DIGIT_NINE) {
        kept[index] = ContractPatterns.ZERO
        index -= 1
    }
    if (index < 0) return ONE_DIGIT to pointPosition + 1
    kept[index] = kept[index] + 1
    return kept.concatToString().trimEnd(ContractPatterns.ZERO) to pointPosition
}

private const val ONE_DIGIT = "1"

private fun parseDigits(digits: String, pointPosition: Int): Double =
    (ContractPatterns.ZERO + ContractPatterns.DECIMAL_POINT.toString() + digits + ContractPatterns.KOTLIN_EXPONENT_MARKER + pointPosition).toDouble()

private fun formatJsNumber(digits: String, pointPosition: Int): String {
    val count = digits.length
    return when {
        pointPosition in count..JS_FIXED_EXPONENT_MAX -> digits + ContractPatterns.ZERO.toString().repeat(pointPosition - count)
        pointPosition in 1..JS_FIXED_EXPONENT_MAX -> digits.substring(0, pointPosition) + ContractPatterns.DECIMAL_POINT + digits.substring(pointPosition)
        pointPosition > JS_FIXED_EXPONENT_MIN && pointPosition <= 0 ->
            ContractPatterns.ZERO.toString() + ContractPatterns.DECIMAL_POINT + ContractPatterns.ZERO.toString().repeat(-pointPosition) + digits
        else -> {
            val exponent = pointPosition - 1
            val sign = if (exponent < 0) ContractPatterns.MINUS else ContractPatterns.PLUS
            val mantissa = if (count == 1) digits else digits.take(1) + ContractPatterns.DECIMAL_POINT + digits.drop(1)
            mantissa + ContractPatterns.JS_EXPONENT_MARKER + sign + abs(exponent)
        }
    }
}
