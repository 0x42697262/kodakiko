package dev.kodakiko.media

import dev.kodakiko.contract.MAX_SAFE_INTEGER

fun requireRange(bytes: ByteArray, offset: Long, length: Long) {
    if (offset < 0 || length < 0 || offset > MAX_SAFE_INTEGER || length > MAX_SAFE_INTEGER || offset + length > bytes.size) {
        throw MediaRangeError(MediaText.OUT_OF_RANGE)
    }
}

fun sliceRange(bytes: ByteArray, start: Long, end: Long): ByteArray {
    requireRange(bytes, start, end - start)
    return bytes.copyOfRange(start.toInt(), end.toInt())
}

fun subarray(bytes: ByteArray, start: Long, end: Long = bytes.size.toLong()): ByteArray {
    val from = start.coerceIn(0L, bytes.size.toLong())
    val to = end.coerceIn(from, bytes.size.toLong())
    return bytes.copyOfRange(from.toInt(), to.toInt())
}

private fun readUnsigned(bytes: ByteArray, offset: Long, length: Int, littleEndian: Boolean): Long {
    requireRange(bytes, offset, length.toLong())
    var value = 0UL
    for (step in 0 until length) {
        val index = if (littleEndian) offset + length - 1 - step else offset + step
        value = value * MEDIA_BYTE_RADIX.toULong() + byteAt(bytes, index).toULong()
    }
    if (value > MAX_SAFE_INTEGER.toULong()) throw MediaRangeError(MediaText.OUT_OF_RANGE)
    return value.toLong()
}

internal fun byteAt(bytes: ByteArray, index: Long): Int = bytes[index.toInt()].toInt() and ByteConstants.BYTE_MASK

fun readUint8(bytes: ByteArray, offset: Long): Long = readUnsigned(bytes, offset, MEDIA_UINT8_BYTES, false)

fun readUint16(bytes: ByteArray, offset: Long, littleEndian: Boolean = false): Long =
    readUnsigned(bytes, offset, MEDIA_UINT16_BYTES, littleEndian)

fun readUint32(bytes: ByteArray, offset: Long, littleEndian: Boolean = false): Long =
    readUnsigned(bytes, offset, MEDIA_UINT32_BYTES, littleEndian)

fun readUint64(bytes: ByteArray, offset: Long): Long = readUnsigned(bytes, offset, MEDIA_UINT64_BYTES, false)

fun readAscii(bytes: ByteArray, offset: Long, length: Long): String {
    requireRange(bytes, offset, length)
    val start = offset.toInt()
    return CharArray(length.toInt()) { (bytes[start + it].toInt() and ByteConstants.BYTE_MASK).toChar() }.concatToString()
}

fun startsWithBytes(bytes: ByteArray, offset: Long, expected: List<Int>): Boolean {
    if (offset < 0 || offset + expected.size > bytes.size) return false
    return expected.indices.all { byteAt(bytes, offset + it) == expected[it] }
}

fun startsWithAscii(bytes: ByteArray, offset: Long, expected: String): Boolean {
    if (offset < 0 || offset + expected.length > bytes.size) return false
    return readAscii(bytes, offset, expected.length.toLong()) == expected
}

internal fun latin1Bytes(text: String): ByteArray = ByteArray(text.length) { (text[it].code and ByteConstants.BYTE_MASK).toByte() }

internal fun writeUint32(target: ByteArray, offset: Int, value: Long) {
    for (index in 0 until MEDIA_UINT32_BYTES) {
        val shift = (MEDIA_UINT32_BYTES - 1 - index) * ByteConstants.BITS_PER_BYTE
        target[offset + index] = (value ushr shift).toByte()
    }
}

internal fun writeUint64(target: ByteArray, offset: Int, value: Long) {
    for (index in 0 until MEDIA_UINT64_BYTES) {
        val shift = (MEDIA_UINT64_BYTES - 1 - index) * ByteConstants.BITS_PER_BYTE
        target[offset + index] = (value ushr shift).toByte()
    }
}

internal fun concatBytes(parts: List<ByteArray>): ByteArray {
    val joined = ByteArray(parts.sumOf { it.size })
    var position = 0
    for (part in parts) {
        part.copyInto(joined, position)
        position += part.size
    }
    return joined
}
