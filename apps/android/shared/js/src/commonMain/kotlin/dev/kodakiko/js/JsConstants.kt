package dev.kodakiko.js

internal object JsDateConstants {
    const val MINUTES_PER_HOUR = 60
    const val MS_PER_SECOND = 1000.0
    const val MS_PER_MINUTE = 60_000.0
    const val MS_PER_HOUR = 3_600_000.0
    const val MS_PER_DAY = 86_400_000.0
    const val MAX_TIME_VALUE = 8.64e15
    const val MONTHS_PER_YEAR = 12.0
    const val DAYS_PER_WEEK = 7.0
    const val EPOCH_WEEKDAY = 4.0
    const val TWO_DIGIT_YEAR_BASE = 1900.0
    const val TWO_DIGIT_YEAR_MAX = 99.0
    const val DAYS_PER_ERA = 146_097.0
    const val YEARS_PER_ERA = 400.0
    const val ERA_DAY_OFFSET = 719_468.0
    const val DAYS_PER_YEAR = 365.0
    const val LEAP_CYCLE_YEARS = 4.0
    const val CENTURY_YEARS = 100.0
    const val ERA_LAST_DAY = 146_096.0
    const val ERA_FOUR_YEAR_DAYS = 1460.0
    const val ERA_CENTURY_DAYS = 36_524.0
    const val MARCH_BASED_MONTH_DAYS = 153.0
    const val MARCH_BASED_MONTH_OFFSET = 2.0
    const val MARCH_BASED_DAY_ROUNDING = 5.0
    const val MARCH_SHIFT_LIMIT = 10.0
    const val MARCH_SHIFT_BACK = 9.0
    const val MARCH_SHIFT_FORWARD = 3.0
    const val MONTH_MAX = 12
    const val DAY_MAX = 31
    const val HOUR_MAX = 24
    const val MINUTE_MAX = 59
    const val SECOND_MAX = 59
    const val OFFSET_HOUR_MAX = 23
    const val MILLISECOND_DIGITS = 3
    const val ISO_YEAR_MAX = 9999.0
    const val YEAR_DIGITS = 4
    const val EXPANDED_YEAR_DIGITS = 6
    const val TWO_DIGITS = 2
    const val EXPANDED_YEAR_ZERO = "-000000"
    const val YEAR_GROUP = 1
    const val MONTH_GROUP = 2
    const val DAY_GROUP = 3
    const val HOUR_GROUP = 4
    const val MINUTE_GROUP = 5
    const val SECOND_GROUP = 6
    const val FRACTION_GROUP = 7
    const val OFFSET_GROUP = 8
    const val OFFSET_HOURS_END = 3
    const val OFFSET_MINUTES_START = 4
    const val INVALID_TIME_VALUE = "Invalid time value"
    const val UTC_DESIGNATOR = "Z"
    const val DATE_SEPARATOR = '-'
    const val TIME_SEPARATOR = ':'
    const val DATE_TIME_SEPARATOR = 'T'
    const val FRACTION_SEPARATOR = '.'
    const val PAD = '0'
    const val PLUS = '+'
    const val MINUS = '-'
    val ISO_PATTERN = Regex(
        "([+-][0-9]{6}|[0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2}))?)?" +
            "(?:[Tt]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]+))?)?([Zz]|[+-][0-9]{2}:[0-9]{2})?)?",
    )
}

internal object JsNumberConstants {
    const val NAN = "NaN"
    const val INFINITY = "Infinity"
    const val NEGATIVE_INFINITY = "-Infinity"
    const val PLUS = '+'
    const val MINUS = '-'
    const val ZERO = '0'
    const val DIGIT_NINE = '9'
    const val ROUND_UP_DIGIT = '5'
    const val DECIMAL_POINT = '.'
    const val JS_EXPONENT_MARKER = 'e'
    const val KOTLIN_EXPONENT_MARKER = 'E'
    const val JSON_NULL = "null"
    const val ONE_DIGIT = "1"
    const val DECIMAL_RADIX = 10
    const val HEX_RADIX = 16
    const val OCTAL_RADIX = 8
    const val BINARY_RADIX = 2
    const val RADIX_PREFIX_LENGTH = 2
    const val HEX_PREFIX = "0x"
    const val OCTAL_PREFIX = "0o"
    const val BINARY_PREFIX = "0b"
    const val FIXED_EXPONENT_MAX = 21
    const val FIXED_EXPONENT_MIN = -6
    const val ROUND_HALF = 0.5
    val DECIMAL = Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
    val INFINITY_PATTERN = Regex("^[+-]?Infinity$")
}

internal const val SURROGATE_PAIR_LENGTH = 2

internal val JS_WHITESPACE: Set<Char> = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
    '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
)
