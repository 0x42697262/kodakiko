package dev.kodakiko.data.net

internal object UriConstants {
    const val ASCII_LIMIT = 0x80
    const val PERCENT = '%'
    const val HEX_DIGITS = "0123456789ABCDEF"
    const val NIBBLE_BITS = 4
    const val NIBBLE_MASK = 0xF
    const val MALFORMED = "URI malformed"
    val UNRESERVED: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-_.!~*'()".toList()).toSet()
}
