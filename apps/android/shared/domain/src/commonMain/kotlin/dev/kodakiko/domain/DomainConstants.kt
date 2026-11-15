package dev.kodakiko.domain

const val TAG_WORD_SEPARATOR = " "
val LOCAL_DATE_PATTERN = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
val LOCAL_TIME_PATTERN = Regex("([0-9]{2}):([0-9]{2})")
const val ISO_DATE_LENGTH = 10
const val ISO_TIME_START = 11
const val ISO_TIME_END = 16
const val MS_PER_MINUTE = 60_000.0
const val HOURS_PER_DAY = 24
const val MINUTES_PER_HOUR = 60
const val YEAR_MIN = 0.0
const val YEAR_MAX = 9999.0
val YEAR_MONTH_PATTERN = Regex("[0-9]{4}-(0[1-9]|1[0-2])")
const val CALENDAR_ROWS = 6
const val DAYS_PER_WEEK = 7
const val MONTHS_PER_YEAR = 12.0
const val YEAR_DIGITS = 4
const val MONTH_DIGITS = 2
const val DATE_PAD_CHAR = '0'
const val YEAR_MONTH_LENGTH = 7
const val DATE_PART_SEPARATOR = "-"
const val FIRST_DAY_OF_MONTH = 1.0
const val MS_PER_DAY = 86_400_000.0

internal object JsNumberText {
    const val NAN = "NaN"
    const val INFINITY = "Infinity"
    const val NEGATIVE_INFINITY = "-Infinity"
    const val PLUS = '+'
    const val MINUS = '-'
    const val DECIMAL_RADIX = 10
}

internal const val SURROGATE_PAIR_LENGTH = 2
