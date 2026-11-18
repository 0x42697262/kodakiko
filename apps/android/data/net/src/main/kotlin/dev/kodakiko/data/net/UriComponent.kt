package dev.kodakiko.data.net

fun encodeUriComponent(text: String): String {
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = codePointAt(text, index)
        if (codePoint < UriConstants.ASCII_LIMIT && codePoint.toChar() in UriConstants.UNRESERVED) {
            builder.append(codePoint.toChar())
        } else {
            String(Character.toChars(codePoint)).encodeToByteArray().forEach { byte ->
                builder.append(UriConstants.PERCENT)
                builder.append(UriConstants.HEX_DIGITS[(byte.toInt() shr UriConstants.NIBBLE_BITS) and UriConstants.NIBBLE_MASK])
                builder.append(UriConstants.HEX_DIGITS[byte.toInt() and UriConstants.NIBBLE_MASK])
            }
        }
        index += Character.charCount(codePoint)
    }
    return builder.toString()
}

private fun codePointAt(text: String, index: Int): Int {
    val char = text[index]
    if (char.isLowSurrogate()) throw UriError()
    if (!char.isHighSurrogate()) return char.code
    val next = text.getOrNull(index + 1)
    if (next == null || !next.isLowSurrogate()) throw UriError()
    return Character.toCodePoint(char, next)
}

class UriError : IllegalArgumentException(UriConstants.MALFORMED)
