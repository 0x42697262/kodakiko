package dev.kodakiko.contract

internal object ContractText {
    const val ROOT_PATH = "$"
    const val REQUIRED = "is required"
    const val EXPECTED_STRING = "must be a string"
    const val EXPECTED_NUMBER = "must be a finite number"
    const val EXPECTED_INTEGER = "must be a safe integer"
    const val EXPECTED_BOOLEAN = "must be a boolean"
    const val EXPECTED_OBJECT = "must be an object"
    const val EXPECTED_ARRAY = "must be an array"
    const val INVALID_FORMAT = "has an invalid format"
    const val INVALID_URL = "must be a URL"
    const val INVALID_OPTION = "is not one of the allowed values"
    const val INVALID_LITERAL = "does not have the required value"
    const val INVALID_DISCRIMINATOR = "does not select a known variant"
    const val PATCH_EMPTY = "must change at least one field"
    const val NOT_JSON = "is not valid JSON"
    const val UNPRINTABLE_NUMBER = "is not a number JSON can print"
    const val JSON_SCHEMA = "ContractJson"
    const val BYTES_SCHEMA = "bytesSchema"
    const val NOT_BASE64URL = "must be non-empty unpadded base64url"

    fun tooShort(minimum: Int): String = "must have at least $minimum characters"

    fun tooLong(maximum: Int): String = "must have at most $maximum characters"

    fun tooFewItems(minimum: Int): String = "must have at least $minimum items"

    fun tooManyItems(maximum: Int): String = "must have at most $maximum items"

    fun tooSmall(minimum: String): String = "must be at least $minimum"

    fun tooBig(maximum: String): String = "must be at most $maximum"

    fun mustStartWith(prefix: String): String = "must start with $prefix"

    fun at(path: String, problem: String): String = "$path $problem"

    fun field(path: String, key: String): String = "$path.$key"

    fun item(path: String, index: Int): String = "$path[$index]"
}
