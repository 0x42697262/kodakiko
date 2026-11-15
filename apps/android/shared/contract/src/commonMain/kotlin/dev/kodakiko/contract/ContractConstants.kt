package dev.kodakiko.contract

const val NOTE_MAX_CHARS = 100_000
const val TAGS_PER_ENTRY_MAX = 50
const val TAG_MAX_CHARS = 64
const val CAPTION_MAX_CHARS = 64
const val MEDIA_PER_ENTRY_HARD_MAX = 100
const val UTC_OFFSET_MINUTES_MIN = -840L
const val UTC_OFFSET_MINUTES_MAX = 840L
const val TIME_ZONE_MAX_CHARS = 64
const val MIME_TYPE_MAX_CHARS = 100
const val FILE_NAME_MAX_CHARS = 255
const val COLLECTION_NAME_MAX_CHARS = 100
const val LATITUDE_MIN = -90.0
const val LATITUDE_MAX = 90.0
const val LONGITUDE_MIN = -180.0
const val LONGITUDE_MAX = 180.0
const val USERNAME_INPUT_MAX = 128
const val DEVICE_LABEL_MAX_CHARS = 64
const val TOKEN_RANDOM_BYTES = 32
const val KDF_OPS_LIMIT_MAX = 2_147_483_647L
const val KDF_WORK_MAX = 4_611_686_018_427_387_904L
const val MIN_OBJECT_BYTES = 40L
const val MEMBERSHIPS_PER_ENTRY_MAX = 10
const val INVITE_MAX_EXPIRY_DAYS = 90L

const val SESSION_TOKEN_PREFIX = "kdk_s_"
const val INVITE_TOKEN_PREFIX = "kdk_i_"
const val ID_PATTERN = "^[A-Za-z0-9_-]{22}$"
const val SESSION_TOKEN_PATTERN = "^kdk_s_[A-Za-z0-9_-]{43}$"
const val INVITE_TOKEN_PATTERN = "^kdk_i_[A-Za-z0-9_-]{43}$"
const val USERNAME_PATTERN = "^[a-z0-9._-]{3,32}$"
const val LINK_TOKEN_HEADER = "x-kodakiko-link-token"
const val LINK_VIEW_TOKEN_HEADER = "x-kodakiko-view-token"

const val API_VERSION = 1
const val ENTRY_METADATA_SCHEMA_VERSION = 1
const val COLLECTION_METADATA_SCHEMA_VERSION = 1
const val ID_BYTES = 16

const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
const val MIN_SAFE_INTEGER = -MAX_SAFE_INTEGER

const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
internal const val BYTES_PER_GROUP = 3
internal const val CHARS_PER_GROUP = 4
internal const val BITS_PER_BYTE = 8
internal const val BITS_PER_CHAR = 6
internal const val BYTE_MASK = 0xff
internal const val CHAR_MASK = 0x3f
internal const val ORPHAN_CHARS_LENGTH_REMAINDER = 1
internal const val BYTES_FROM_TWO_CHARS = 1
internal const val BYTES_FROM_THREE_CHARS = 2
internal const val CHARS_FOR_TWO_BYTES_GROUP = 3

internal const val JSON_CONTROL_CHAR_LIMIT = '\u0020'
internal const val JSON_UNICODE_ESCAPE_DIGITS = 4
internal const val HEX_RADIX = 16
internal const val DECIMAL_RADIX = 10
internal const val OCTAL_RADIX = 8
internal const val BINARY_RADIX = 2
internal const val RADIX_PREFIX_LENGTH = 2

internal const val JS_FIXED_EXPONENT_MAX = 21
internal const val JS_FIXED_EXPONENT_MIN = -6
internal const val JS_ROUND_UP_DIGIT = '5'
internal const val JS_DIGIT_NINE = '9'

internal val JS_WHITESPACE: Set<Char> = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
    '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
)

internal const val URL_PORT_MAX = 65_535
internal const val URL_PORT_DIGITS_MAX = 5
internal const val URL_C0_CONTROL_MAX = '\u001F'
internal const val URL_DELETE = '\u007F'
internal const val URL_PERCENT_ENCODED_LENGTH = 3
internal const val IPV4_PARTS_MAX = 4
internal const val IPV4_PART_MAX = 255L
internal const val IPV4_PART_BASE = 256L
internal const val IPV4_VALUE_CAP = 1L shl 40
internal const val IPV6_PIECES = 8
internal const val IPV6_COMPRESSION_LENGTH = 2
internal const val SURROGATE_PAIR_LENGTH = 2
internal const val IPV6_PIECE_HEX_DIGITS = 4
internal const val IPV6_LAST_IPV4_PIECE = 6
internal const val IPV6_IPV4_PARTS = 4
internal const val IPV6_IPV4_PARTS_PER_PIECE = 2

internal const val PUNYCODE_BASE = 36
internal const val PUNYCODE_T_MIN = 1
internal const val PUNYCODE_T_MAX = 26
internal const val PUNYCODE_SKEW = 38
internal const val PUNYCODE_DAMP = 700
internal const val PUNYCODE_INITIAL_BIAS = 72
internal const val PUNYCODE_INITIAL_N = 128
internal const val PUNYCODE_DIGIT_LETTERS = 26
internal const val PUNYCODE_HALF = 2
internal const val UNICODE_MAX_CODE_POINT = 0x10FFFF
internal const val IDNA_C1_CONTROL_MIN = '\u0080'
internal const val IDNA_C1_CONTROL_MAX = '\u009F'
internal val IDNA_IGNORED: Set<Char> = setOf(
    '\u00AD', '\u034F', '\u180B', '\u180C', '\u180D', '\u180E', '\u200B', '\u2060', '\uFEFF',
    '\uFE00', '\uFE01', '\uFE02', '\uFE03', '\uFE04', '\uFE05', '\uFE06', '\uFE07',
    '\uFE08', '\uFE09', '\uFE0A', '\uFE0B', '\uFE0C', '\uFE0D', '\uFE0E', '\uFE0F',
)
internal val IDNA_JOINERS: Set<Char> = setOf('\u200C', '\u200D')
internal const val IDNA_FULLWIDTH_MIN = '\uFF01'
internal const val IDNA_FULLWIDTH_MAX = '\uFF5E'
internal const val IDNA_FULLWIDTH_OFFSET = 0xFEE0
internal val IDNA_FULL_STOPS: Set<Char> = setOf('\u3002', '\uFF0E', '\uFF61')
