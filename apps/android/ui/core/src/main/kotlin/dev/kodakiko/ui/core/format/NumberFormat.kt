package dev.kodakiko.ui.core.format

import dev.kodakiko.data.db.TimeFormat
import dev.kodakiko.js.jsIntegerString
import dev.kodakiko.js.jsSlice
import dev.kodakiko.js.jsStringToNumber
import dev.kodakiko.js.jsToFixed
import dev.kodakiko.js.jsonStringifyNumber
import kotlin.math.floor

private fun jsNumberText(value: Double): String = if (value.isFinite()) jsonStringifyNumber(value) else jsIntegerString(value)

private fun isInteger(value: Double): Boolean = value.isFinite() && value == floor(value)

fun formatBytes(bytes: Long): String {
    var value = maxOf(0L, bytes).toDouble()
    var unit = 0
    while (value >= FormatConstants.BYTES_STEP && unit < FormatConstants.BYTE_UNITS.lastIndex) {
        value /= FormatConstants.BYTES_STEP
        unit += 1
    }
    val label = FormatConstants.BYTE_UNITS[unit]
    if (unit == 0 || isInteger(value)) return jsNumberText(value) + FormatConstants.UNIT_SEPARATOR + label
    val rounded = jsStringToNumber(jsToFixed(value, FormatConstants.BYTE_DECIMALS))
    val text = if (isInteger(rounded)) jsNumberText(rounded) else jsToFixed(rounded, FormatConstants.BYTE_DECIMALS)
    return text + FormatConstants.UNIT_SEPARATOR + label
}

private fun timerPart(value: Long): String = value.toString().padStart(FormatConstants.TIMER_DIGITS, FormatConstants.TIMER_PAD)

fun formatDuration(milliseconds: Long): String {
    val totalSeconds = maxOf(0L, milliseconds) / FormatConstants.MILLISECONDS_PER_TIMER_SECOND
    val minutes = totalSeconds / FormatConstants.TIMER_SECONDS_PER_MINUTE
    val seconds = totalSeconds % FormatConstants.TIMER_SECONDS_PER_MINUTE
    return timerPart(minutes) + FormatConstants.TIMER_SEPARATOR + timerPart(seconds)
}

fun formatClock(time: String, format: TimeFormat): String {
    if (format == TimeFormat.HOURS_24) return time
    val hour = jsStringToNumber(jsSlice(time, 0, FormatConstants.HOUR_CHARS))
    val minutes = jsSlice(time, FormatConstants.MINUTE_START)
    val suffix = if (hour >= FormatConstants.NOON) FormatConstants.AFTER_NOON else FormatConstants.BEFORE_NOON
    val twelve = if (hour % FormatConstants.NOON == 0.0) FormatConstants.NOON else hour % FormatConstants.NOON
    return jsNumberText(twelve) + FormatConstants.CLOCK_SEPARATOR + minutes + FormatConstants.SUFFIX_SEPARATOR + suffix
}
