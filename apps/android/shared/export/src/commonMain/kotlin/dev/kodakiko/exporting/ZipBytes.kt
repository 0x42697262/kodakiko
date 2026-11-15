package dev.kodakiko.exporting

import dev.kodakiko.exporting.ZipConstants.BYTE_BITS
import dev.kodakiko.exporting.ZipConstants.BYTE_MASK
import dev.kodakiko.exporting.ZipConstants.CRC_BITS
import dev.kodakiko.exporting.ZipConstants.CRC_POLYNOMIAL
import dev.kodakiko.exporting.ZipConstants.CRC_TABLE_SIZE
import dev.kodakiko.exporting.ZipConstants.U16_BYTES
import dev.kodakiko.exporting.ZipConstants.U32_BYTES
import dev.kodakiko.exporting.ZipConstants.U32_MAX
import dev.kodakiko.exporting.ZipConstants.UTF8_CONTINUATION
import dev.kodakiko.exporting.ZipConstants.UTF8_CONTINUATION_MASK
import dev.kodakiko.exporting.ZipConstants.UTF8_FOUR_BYTE_LEAD
import dev.kodakiko.exporting.ZipConstants.UTF8_ONE_BYTE_MAX
import dev.kodakiko.exporting.ZipConstants.UTF8_SHIFT_ONE
import dev.kodakiko.exporting.ZipConstants.UTF8_SHIFT_THREE
import dev.kodakiko.exporting.ZipConstants.UTF8_SHIFT_TWO
import dev.kodakiko.exporting.ZipConstants.UTF8_THREE_BYTE_LEAD
import dev.kodakiko.exporting.ZipConstants.UTF8_THREE_BYTE_MAX
import dev.kodakiko.exporting.ZipConstants.UTF8_TWO_BYTE_LEAD
import dev.kodakiko.exporting.ZipConstants.UTF8_TWO_BYTE_MAX

internal class Crc32 {
    private var state = U32_MAX

    fun update(bytes: ByteArray) {
        for (byte in bytes) {
            val index = ((state xor (byte.toLong() and BYTE_MASK.toLong())) and BYTE_MASK.toLong()).toInt()
            state = TABLE[index] xor (state ushr CRC_BITS)
        }
    }

    fun value(): Long = state xor U32_MAX

    private companion object {
        val TABLE = LongArray(CRC_TABLE_SIZE) { index ->
            var value = index.toLong()
            repeat(CRC_BITS) { value = if (value and 1L != 0L) CRC_POLYNOMIAL xor (value ushr 1) else value ushr 1 }
            value
        }
    }
}

internal class LittleEndianWriter(size: Int) {
    val bytes = ByteArray(size)
    private var position = 0

    fun u16(value: Int): LittleEndianWriter = integer(value.toLong(), U16_BYTES)

    fun u32(value: Long): LittleEndianWriter = integer(value, U32_BYTES)

    fun raw(data: ByteArray): LittleEndianWriter {
        data.copyInto(bytes, position)
        position += data.size
        return this
    }

    private fun integer(value: Long, width: Int): LittleEndianWriter {
        for (shift in 0 until width) bytes[position + shift] = (value ushr (shift * BYTE_BITS)).toInt().toByte()
        position += width
        return this
    }
}

internal fun readU16(data: ByteArray, offset: Int): Int {
    if (offset < 0 || offset + U16_BYTES > data.size) zipMalformed()
    return (data[offset].toInt() and BYTE_MASK) or ((data[offset + 1].toInt() and BYTE_MASK) shl BYTE_BITS)
}

internal fun readU32(data: ByteArray, offset: Int): Long = readU16(data, offset).toLong() or (readU16(data, offset + U16_BYTES).toLong() shl (U16_BYTES * BYTE_BITS))

internal fun zipMalformed(): Nothing = throw ZipFormatException(ExportText.ZIP_MALFORMED)

internal fun utf8Bytes(text: String): ByteArray {
    val out = ArrayList<Byte>(text.length)
    fun continuation(codePoint: Int, shift: Int) = out.add((UTF8_CONTINUATION or ((codePoint shr shift) and UTF8_CONTINUATION_MASK)).toByte())
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val pair = char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
        val codePoint = when {
            pair -> ((char - Char.MIN_HIGH_SURROGATE) shl ZipConstants.SURROGATE_SHIFT) + (text[index + 1] - Char.MIN_LOW_SURROGATE) + ZipConstants.SUPPLEMENTARY_BASE
            char.isSurrogate() -> ZipConstants.REPLACEMENT_CHARACTER.code
            else -> char.code
        }
        index += if (pair) ZipConstants.SURROGATE_PAIR_LENGTH else 1
        when {
            codePoint <= UTF8_ONE_BYTE_MAX -> out.add(codePoint.toByte())
            codePoint <= UTF8_TWO_BYTE_MAX -> {
                out.add((UTF8_TWO_BYTE_LEAD or (codePoint shr UTF8_SHIFT_ONE)).toByte())
                continuation(codePoint, 0)
            }
            codePoint <= UTF8_THREE_BYTE_MAX -> {
                out.add((UTF8_THREE_BYTE_LEAD or (codePoint shr UTF8_SHIFT_TWO)).toByte())
                continuation(codePoint, UTF8_SHIFT_ONE)
                continuation(codePoint, 0)
            }
            else -> {
                out.add((UTF8_FOUR_BYTE_LEAD or (codePoint shr UTF8_SHIFT_THREE)).toByte())
                continuation(codePoint, UTF8_SHIFT_TWO)
                continuation(codePoint, UTF8_SHIFT_ONE)
                continuation(codePoint, 0)
            }
        }
    }
    return out.toByteArray()
}

internal fun latin1String(bytes: ByteArray): String = buildString(bytes.size) { for (byte in bytes) append((byte.toInt() and BYTE_MASK).toChar()) }
