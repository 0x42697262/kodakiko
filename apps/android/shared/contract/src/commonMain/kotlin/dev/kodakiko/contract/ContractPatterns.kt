package dev.kodakiko.contract

internal object ContractPatterns {
    val ID = Regex(ID_PATTERN)
    val BASE64URL = Regex("^[A-Za-z0-9_-]*$")
    val SESSION_TOKEN = Regex(SESSION_TOKEN_PATTERN)
    val INVITE_TOKEN = Regex(INVITE_TOKEN_PATTERN)
    val USERNAME = Regex(USERNAME_PATTERN)
    val ISO_DATETIME = Regex(
        "^(?:(?:[0-9][0-9][2468][048]|[0-9][0-9][13579][26]|[0-9][0-9]0[48]|[02468][048]00|[13579][26]00)-02-29" +
            "|[0-9]{4}-(?:(?:0[13578]|1[02])-(?:0[1-9]|[12][0-9]|3[01])|(?:0[469]|11)-(?:0[1-9]|[12][0-9]|30)|(?:02)-(?:0[1-9]|1[0-9]|2[0-8])))" +
            "T(?:(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]+)?(?:Z))$",
    )
    val JSON_NUMBER = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")
    val URL_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    val ASCII_DIGITS = Regex("^[0-9]+$")

    const val HEX_PREFIX = "0x"
    const val JSON_TRUE = "true"
    const val JSON_FALSE = "false"
    const val JSON_NULL = "null"
    const val JS_OBJECT_STRING = "[object Object]"
    const val JS_ARRAY_SEPARATOR = ","
    const val DECIMAL_POINT = '.'
    const val MINUS = '-'
    const val PLUS = '+'
    const val ZERO = '0'

    const val URL_FILE_SCHEME = "file"
    val URL_SPECIAL_SCHEMES = setOf("ftp", "http", "https", "ws", "wss")
    const val URL_AUTHORITY_SLASHES = "//"
    val URL_SLASHES = setOf('/', '\\')
    val URL_AUTHORITY_END = setOf('/', '\\', '?', '#')
    val URL_NON_SPECIAL_AUTHORITY_END = setOf('/', '?', '#')
    const val URL_CREDENTIALS_END = '@'
    const val URL_PORT_SEPARATOR = ':'
    const val URL_IPV6_START = '['
    const val URL_IPV6_END = ']'
    const val URL_PERCENT = '%'
    const val IDNA_ACE_PREFIX = "xn--"
    const val PUNYCODE_DELIMITER = '-'
    const val URL_LABEL_SEPARATOR = '.'
    val URL_TAB_AND_NEWLINE = setOf('\t', '\n', '\r')
    val URL_FORBIDDEN_HOST = setOf('\u0000', '\t', '\n', '\r', ' ', '#', '/', ':', '<', '>', '?', '@', '[', '\\', ']', '^', '|')
}
