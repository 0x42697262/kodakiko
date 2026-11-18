package dev.kodakiko.crypto

const val KEY_BYTES = 32
const val AEAD_NONCE_BYTES = 24
const val AEAD_TAG_BYTES = 16
const val SALT_BYTES = 16
const val FORMAT_VERSION_SEALED_BLOB = 0x01
const val BLOB_VERSION_BYTES = 1
const val BLOB_VERSION_OFFSET = 0
const val FORMAT_VERSION_SHARE_PAYLOAD = 0x01
const val SHARE_PAYLOAD_ID_LENGTH_OFFSET = 1
const val SHARE_PAYLOAD_ID_OFFSET = 2

val STREAM_MAGIC: ByteArray get() = byteArrayOf(0x4b, 0x44, 0x4b, 0x53)

const val STREAM_VERSION = 0x01
const val STREAM_CHUNK_SIZE_EXPONENT = 20
const val STREAM_CHUNK_SIZE_EXPONENT_MIN = 10
const val STREAM_CHUNK_SIZE_EXPONENT_MAX = 24
const val STREAM_NONCE_PREFIX_BYTES = 16
const val STREAM_COUNTER_BYTES = 7
const val STREAM_HEADER_BYTES = 24
const val STREAM_HEADER_MAGIC_OFFSET = 0
const val STREAM_HEADER_VERSION_OFFSET = 4
const val STREAM_HEADER_EXPONENT_OFFSET = 5
const val STREAM_HEADER_RESERVED_OFFSET = 6
const val STREAM_HEADER_RESERVED_BYTES = 2
const val STREAM_HEADER_PREFIX_OFFSET = 8
const val STREAM_FINAL_FLAG = 0x01
const val STREAM_NOT_FINAL_FLAG = 0x00
const val STREAM_MAX_CHUNK_INDEX = 9_007_199_254_740_991L
const val STREAM_MIN_OBJECT_BYTES = 40
const val STREAM_CHUNK_SIZE_BASE = 2
const val PADDING_BLOCK_BYTES = 256
const val PADDING_MARKER = 0x80
const val BYTE_BITS = 8
const val BYTE_VALUES = 256
const val BYTE_MASK = 0xff
const val ARGON2ID_MIN_MEM_BYTES = 134_217_728L
const val ARGON2ID_MIN_WORK = 4_294_967_296L
const val ARGON2ID_START_MEM_BYTES = 268_435_456L
const val ARGON2ID_START_OPS = 16L
const val RECOVERY_WORD_COUNT = 24
const val RECOVERY_WORD_BITS = 11
const val RECOVERY_CHECKSUM_BITS = 8
const val RECOVERY_WORDLIST_SIZE = 2048
const val GENERIC_HASH_BYTES = 32
const val FINGERPRINT_BYTES = 8
const val AD_SEPARATOR = 0x00
const val HEX_RADIX = 16
const val HEX_CHARS_PER_BYTE = 2
const val FINGERPRINT_GROUPS = 4
const val FINGERPRINT_GROUP_CHARS = 4
const val FINGERPRINT_SEPARATOR = " "
const val VECTOR_FORMAT = 1

internal const val HEX_DIGITS = "0123456789abcdef"
internal const val UTF8_REPLACEMENT_CHARACTER = '\uFFFD'
internal val UTF8_BYTE_ORDER_MARK: ByteArray get() = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
internal val HEX_PATTERN = Regex("[0-9a-fA-F]*")
internal val ID_PATTERN = Regex("[A-Za-z0-9_-]{22}")
internal val RECOVERY_INPUT_WHITESPACE = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

internal const val SODIUM_LIBRARY_NAME = "sodium"
internal const val SODIUM_VERSION_SYMBOL = "sodium_version_string"
internal const val SODIUM_SUCCESS = 0
internal const val ARGON2ID13_ALGORITHM = 2
internal const val ARGON2ID_OPS_LIMIT_MIN = 1L
internal const val BOX_PUBLIC_KEY_BYTES = 32
internal const val BOX_SECRET_KEY_BYTES = 32
internal const val BOX_SEED_BYTES = 32
internal const val BOX_SEAL_BYTES = 48
internal const val KDF_CONTEXT_BYTES = 8
internal const val KDF_KEY_BYTES = 32
internal const val KDF_SUBKEY_BYTES_MIN = 16
internal const val KDF_SUBKEY_BYTES_MAX = 64
internal const val GENERIC_HASH_BYTES_MIN = 16
internal const val GENERIC_HASH_BYTES_MAX = 64
internal const val ARGON2ID_OUTPUT_BYTES_MIN = 16

enum class KdfContext(val value: String) {
    LOGIN("kdklogin"),
    RECOVERY("kdkrecov"),
    MEDIA("kdkmedia"),
    LINK("kdklink_"),
}

object KdfSubkeyIds {
    const val LOGIN = 1L
    const val RECOVERY_LOGIN = 1L
    const val MEDIA_ORIGINAL = 1L
    const val MEDIA_THUMBNAIL = 2L
    const val LINK_COLLECTION_KEY = 1L
    const val LINK_ACCESS_TOKEN = 2L
}

enum class AdContext(val value: String) {
    MASTER_KEY_BY_KEK("kodakiko/v1/wrap/master-key-by-kek"),
    MASTER_KEY_BY_RECOVERY("kodakiko/v1/wrap/master-key-by-recovery"),
    RECOVERY_KEY_BY_MASTER("kodakiko/v1/wrap/recovery-key-by-master"),
    PRIVATE_KEY_BY_MASTER("kodakiko/v1/wrap/private-key-by-master"),
    COLLECTION_KEY_BY_MASTER("kodakiko/v1/wrap/collection-key-by-master"),
    ENTRY_KEY_BY_COLLECTION("kodakiko/v1/wrap/entry-key-by-collection"),
    MEDIA_KEY_BY_ENTRY("kodakiko/v1/wrap/media-key-by-entry"),
    ENTRY_METADATA("kodakiko/v1/entry-metadata"),
    COLLECTION_METADATA("kodakiko/v1/collection-metadata"),
    USER_SETTINGS("kodakiko/v1/user-settings"),
    INDEX_SNAPSHOT("kodakiko/v1/index-snapshot"),
    LOCAL_KEY_BY_DEVICE("kodakiko/v1/wrap/local-thumbnail-key"),
    LINK_SECRET_BY_MASTER("kodakiko/v1/wrap/link-secret-by-master"),
}
