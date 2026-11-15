package dev.kodakiko.contract

private val CHAR_VALUES: Map<Char, Int> = BASE64URL_ALPHABET.withIndex().associate { (position, char) -> char to position }

fun isDecodableBase64Url(text: String): Boolean =
    ContractPatterns.BASE64URL.matches(text) && text.length % CHARS_PER_GROUP != ORPHAN_CHARS_LENGTH_REMAINDER

fun isValidId(text: String): Boolean = ContractPatterns.ID.matches(text)

fun encodeBytes(bytes: ByteArray): String {
    val encoded = StringBuilder()
    for (start in bytes.indices step BYTES_PER_GROUP) {
        val available = minOf(BYTES_PER_GROUP, bytes.size - start)
        var bits = 0
        for (offset in 0 until BYTES_PER_GROUP) {
            val byte = if (start + offset < bytes.size) bytes[start + offset].toInt() and BYTE_MASK else 0
            bits = (bits shl BITS_PER_BYTE) or byte
        }
        for (position in 0..available) {
            val shift = BITS_PER_CHAR * (CHARS_PER_GROUP - 1 - position)
            encoded.append(BASE64URL_ALPHABET[(bits shr shift) and CHAR_MASK])
        }
    }
    return encoded.toString()
}

fun decodeBytes(text: String): ByteArray {
    if (text.isEmpty() || !isDecodableBase64Url(text)) {
        throw ContractViolation(ContractText.BYTES_SCHEMA, ContractText.NOT_BASE64URL)
    }
    val output = ArrayList<Byte>(text.length)
    for (start in text.indices step CHARS_PER_GROUP) {
        val group = text.substring(start, minOf(text.length, start + CHARS_PER_GROUP))
        var bits = 0
        for (position in 0 until CHARS_PER_GROUP) {
            bits = (bits shl BITS_PER_CHAR) or (group.getOrNull(position)?.let { CHAR_VALUES[it] } ?: 0)
        }
        for (index in 0 until bytesInGroup(group.length)) {
            val shift = BITS_PER_BYTE * (BYTES_PER_GROUP - 1 - index)
            output += ((bits shr shift) and BYTE_MASK).toByte()
        }
    }
    return output.toByteArray()
}

private fun bytesInGroup(characters: Int): Int =
    when (characters) {
        CHARS_PER_GROUP -> BYTES_PER_GROUP
        CHARS_FOR_TWO_BYTES_GROUP -> BYTES_FROM_THREE_CHARS
        else -> BYTES_FROM_TWO_CHARS
    }
