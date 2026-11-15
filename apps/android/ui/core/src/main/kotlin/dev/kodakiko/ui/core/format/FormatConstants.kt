package dev.kodakiko.ui.core.format

import java.time.format.DateTimeFormatter
import java.util.Locale

object FormatConstants {
    const val BYTES_STEP = 1024.0
    val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")
    const val BYTE_DECIMALS = 1
    const val UNIT_SEPARATOR = " "

    const val MILLISECONDS_PER_TIMER_SECOND = 1000L
    const val TIMER_SECONDS_PER_MINUTE = 60L
    const val TIMER_DIGITS = 2
    const val TIMER_PAD = '0'
    const val TIMER_SEPARATOR = ":"

    const val NOON = 12.0
    const val HOUR_CHARS = 2
    const val MINUTE_START = 3
    const val BEFORE_NOON = "AM"
    const val AFTER_NOON = "PM"
    const val CLOCK_SEPARATOR = ":"
    const val SUFFIX_SEPARATOR = " "
    const val NOT_A_NUMBER = "NaN"

    const val MS_PER_MINUTE = 60_000L
    const val MS_PER_DAY = 86_400_000.0
    const val MIDNIGHT_SUFFIX = "T00:00:00.000Z"
    const val ISO_DATE_LENGTH = 10
    const val YEAR_LENGTH = 4
    const val INVALID_TIME_VALUE = "Invalid time value"

    val MONTH_KEY = Regex("^m:([0-9]{4}-[0-9]{2})$")
    val YEAR_KEY = Regex("^y:([0-9]{4})$")
    val TAG_KEY = Regex("^t:([^\\n\\r\\u2028\\u2029]+)$")
    const val UNTAGGED_KEY = "n:"
    const val FIRST_OF_MONTH = "-01"
    const val TAG_PREFIX = "#"

    const val STORAGE_STATE_SEPARATOR = ", "

    private val ENGLISH: Locale = Locale.US
    val LONG_WEEKDAY_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", ENGLISH)
    val SHORT_WEEKDAY_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", ENGLISH)
    val FULL_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, y", ENGLISH)
    val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM y", ENGLISH)
}
