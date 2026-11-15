package dev.kodakiko.exporting

import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.js.JsDate

const val EXPORT_FILE_OVERHEAD_BYTES = 1024L
const val EXPORT_PART_MAX_FILES = 65_000L
const val EXPORT_PART_MAX_BYTES = 4_026_531_840L
const val EXPORT_WEB_MEMORY_PART_MAX_BYTES = 1_073_741_824L
const val EXPORT_NOTE_FILE = "entry.md"
const val EXPORT_README_FILE = "README.txt"
const val EXPORT_MISSING_FILE = "MISSING.txt"
const val EXPORT_FALLBACK_EXTENSION = "bin"
const val EXPORT_MTIME_MIN_ISO = "1980-01-01T12:00:00.000Z"
const val EXPORT_MTIME_MAX_ISO = "2099-12-31T12:00:00.000Z"

val EXPORT_MTIME_MIN: Double = JsDate.parse(EXPORT_MTIME_MIN_ISO)
val EXPORT_MTIME_MAX: Double = JsDate.parse(EXPORT_MTIME_MAX_ISO)

val EXPORT_EXTENSIONS: Map<String, String> = linkedMapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/heic" to "heic",
    "image/heif" to "heif",
    "video/mp4" to "mp4",
    "video/quicktime" to "mov",
    "video/webm" to "webm",
    "audio/mp4" to "m4a",
    "audio/webm" to "weba",
    "audio/ogg" to "ogg",
)

const val KDKX_EXTENSION = ".kdkx"
const val KDKX_CHUNK_BYTES = 1 shl STREAM_CHUNK_SIZE_EXPONENT
const val KDKX_SALT_BYTES = 16

internal object LayoutConstants {
    const val MS_PER_MINUTE = 60_000.0
    const val MINUTES_PER_HOUR = 60L
    const val POSITION_DIGITS = 3
    const val PART_DIGITS = 2
    const val TWO_DIGITS = 2
    const val ID_PREFIX_CHARS = 8
    const val COORDINATE_DECIMALS = 5
    const val DATE_CHARS = 10
    const val TIME_START = 11
    const val TIME_END = 19
    const val HOUR_MINUTE_CHARS = 5
    const val YEAR_PART = 0
    const val MONTH_PART = 1
    const val DAY_PART = 2
    const val PAD = '0'
    const val PLUS = "+"
    const val MINUS = "-"
    const val TIME_SEPARATOR = ":"
    const val DATE_SEPARATOR = "-"
    const val DATE_TIME_SEPARATOR = "T"
    const val PATH_SEPARATOR = "/"
    const val FOLDER_PART_SEPARATOR = "_"
    const val EXTENSION_SEPARATOR = "."
    const val LINE_BREAK = "\n"
    const val LIST_SEPARATOR = ", "
    const val FENCE = "---"
    const val YAML_QUOTE = "\""
    const val YAML_BACKSLASH = "\\"
    const val YAML_ESCAPED_QUOTE = "\\\""
    const val YAML_ESCAPED_BACKSLASH = "\\\\"
    const val YAML_NEEDS_QUOTES = ":#[]{},\"'"
    const val FAVORITE_LINE = "favorite: true"
}

internal object ParseConstants {
    const val FENCE = "---"
    const val FENCE_LINE = "---\n"
    const val FENCE_BREAK = "\n---\n"
    const val LINE_BREAK = "\n"
    const val KEY_SEPARATOR = ':'
    const val UTC_DESIGNATOR = "Z"
    const val NEGATIVE_SIGN = "-"
    const val SIGN_NEGATIVE = -1L
    const val SIGN_POSITIVE = 1L
    const val MINUTES_PER_HOUR = 60L
    const val MS_PER_MINUTE = 60_000.0
    const val DATE_GROUP = 1
    const val SIGN_GROUP = 2
    const val HOURS_GROUP = 3
    const val MINUTES_GROUP = 4
    const val EMPTY_LIST = "[]"
    const val TRUE = "true"
    const val FIELD_ID = "id"
    const val FIELD_DATE = "date"
    const val FIELD_TAGS = "tags"
    const val FIELD_MEDIA = "media"
    const val FIELD_LOCATION = "location"
    const val FIELD_FAVORITE = "favorite"
    const val FIELD_LATITUDE = "latitude"
    const val FIELD_LONGITUDE = "longitude"
    const val FIELD_SOURCE = "source"
    const val FIELD_KIND = "kind"
    const val FIELD_CAPTION = "caption"
    const val FIELD_FILE = "file"
    const val FIELD_ORIGINAL_NAME = "original_name"
    const val FLOW_SPACE = ' '
    const val FLOW_QUOTE = '"'
    const val FLOW_ESCAPE = '\\'
    const val FLOW_LIST_START = '['
    const val FLOW_LIST_END = ']'
    const val FLOW_MAP_START = '{'
    const val FLOW_MAP_END = '}'
    const val FLOW_ITEM_SEPARATOR = ','
    const val FLOW_KEY_SEPARATOR = ':'
    val DATE_PATTERN = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})([+-])([0-9]{2}):([0-9]{2})")
}

internal object KdkxConstants {
    const val MAGIC_TEXT = "KDKX"
    const val VERSION = 1
    const val U32_BYTES = 4
    const val VERSION_OFFSET = 4
    const val OPS_OFFSET = VERSION_OFFSET + 1
    const val MEM_OFFSET = OPS_OFFSET + U32_BYTES
    const val SALT_OFFSET = MEM_OFFSET + U32_BYTES
    const val PREFIX_BYTES = SALT_OFFSET + KDKX_SALT_BYTES
    const val BYTE_BITS = 8
    const val BYTE_MASK = 0xFF
    const val U32_MASK = 0xFFFF_FFFFL
}
