package dev.kodakiko.crypto

fun concatBytes(vararg parts: ByteArray): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

fun utf8Encode(text: String): ByteArray = replaceLoneSurrogates(text).encodeToByteArray()

private fun replaceLoneSurrogates(text: String): String {
    if (text.none { it.isSurrogate() }) return text
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)
        when {
            char.isHighSurrogate() && next != null && next.isLowSurrogate() -> {
                builder.append(char).append(next)
                index += 1
            }
            char.isSurrogate() -> builder.append(UTF8_REPLACEMENT_CHARACTER)
            else -> builder.append(char)
        }
        index += 1
    }
    return builder.toString()
}

fun utf8Decode(bytes: ByteArray): String {
    val bom = UTF8_BYTE_ORDER_MARK
    val start = if (bytes.size >= bom.size && bytesEqual(bytes.copyOfRange(0, bom.size), bom)) bom.size else 0
    return try {
        bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    }
}

fun bytesEqual(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var difference = 0
    for (index in left.indices) {
        difference = difference or (left[index].toInt() xor right[index].toInt())
    }
    return difference == 0
}

fun writeUint56BigEndian(target: ByteArray, offset: Int, value: Long) {
    if (value < 0 || value > STREAM_MAX_CHUNK_INDEX) throw InvalidChunkError(CryptoText.INVALID_CHUNK)
    var remaining = value
    for (position in STREAM_COUNTER_BYTES - 1 downTo 0) {
        target[offset + position] = (remaining and BYTE_MASK.toLong()).toByte()
        remaining = remaining ushr BYTE_BITS
    }
}

fun wipe(bytes: ByteArray) {
    bytes.fill(0)
}

fun bytesToHex(bytes: ByteArray): String {
    val builder = StringBuilder(bytes.size * HEX_CHARS_PER_BYTE)
    for (byte in bytes) {
        val value = byte.toInt() and BYTE_MASK
        builder.append(HEX_DIGITS[value / HEX_RADIX]).append(HEX_DIGITS[value % HEX_RADIX])
    }
    return builder.toString()
}

fun hexToBytes(hex: String): ByteArray {
    if (hex.length % HEX_CHARS_PER_BYTE != 0 || !HEX_PATTERN.matches(hex)) {
        throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    }
    return ByteArray(hex.length / HEX_CHARS_PER_BYTE) { index ->
        val start = index * HEX_CHARS_PER_BYTE
        hex.substring(start, start + HEX_CHARS_PER_BYTE).toInt(HEX_RADIX).toByte()
    }
}
