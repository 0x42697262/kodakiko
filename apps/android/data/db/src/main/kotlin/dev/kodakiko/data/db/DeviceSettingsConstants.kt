package dev.kodakiko.data.db

object DeviceSettingsConstants {
    const val SCHEMA_VERSION = 1
    const val ORIGINAL_CACHE_CAP_BYTES_MIN = 0L
    const val MIN_LARGE_UPLOAD_THRESHOLD_BYTES = 1_048_576L
    const val AUTO_LOCK_MINUTES_MIN = 1L
    const val AUTO_LOCK_MINUTES_MAX = 60L
    const val GALLERY_COLUMNS_AUTO = 0L
    const val GALLERY_COLUMNS_MAX = 10L
    const val JS_MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    val HOLD_TO_RECORD_SECONDS: List<Int> = listOf(5, 10, 15, 30, 60, 120)
    const val BADGE_FAVORITE_DEFAULT = true
    val FILTER_FAVORITE_DEFAULT = TriState.OFF

    const val ANDROID_ORIGINAL_CACHE_CAP_BYTES = 2_147_483_648L
    const val ANDROID_LARGE_UPLOAD_THRESHOLD_BYTES = 104_857_600L
    const val ANDROID_AUTO_LOCK_MINUTES = 5
    const val ANDROID_GALLERY_COLUMNS = 0
    const val ANDROID_HOLD_TO_RECORD_MAX_SECONDS = 15
}

internal object JsJsonConstants {
    const val HEX_RADIX = 16
    const val UNICODE_ESCAPE_DIGITS = 4
    const val FIRST_NON_CONTROL = 0x20
    const val NIBBLE_BITS = 4
    const val NIBBLE_MASK = 0xf
    const val BYTE_ORDER_MARK = '\uFEFF'
    const val HEX_DIGITS = "0123456789abcdef"
    const val UNICODE_ESCAPE_PREFIX = "\\u"
    const val LITERAL_TRUE = "true"
    const val LITERAL_FALSE = "false"
    const val LITERAL_NULL = "null"
    val NUMBER = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
    val NUMBER_PREFIX = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    val SHORT_ESCAPES: Map<Char, Char> = mapOf(
        '"' to '"',
        '\\' to '\\',
        '/' to '/',
        'b' to '\b',
        'f' to '\u000C',
        'n' to '\n',
        'r' to '\r',
        't' to '\t',
    )
    val SHORT_QUOTES: Map<Char, String> = mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\b' to "\\b",
        '\u000C' to "\\f",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t",
    )
    val WHITESPACE: Set<Char> = setOf(' ', '\t', '\n', '\r')
}
