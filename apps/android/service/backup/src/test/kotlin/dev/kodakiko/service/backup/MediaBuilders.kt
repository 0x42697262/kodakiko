package dev.kodakiko.service.backup

private const val BOX_HEADER_BYTES = 8
private const val MVHD_V0_PAYLOAD_BYTES = 100

fun joinBytes(vararg parts: ByteArray): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

fun asciiBytes(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

fun u32be(value: Long): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

fun seededBytes(length: Int, seed: Int): ByteArray {
    val bytes = ByteArray(length)
    var state = if (seed == 0) 1 else seed
    for (index in 0 until length) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        bytes[index] = state.toByte()
    }
    return bytes
}

fun isoBox(type: String, payload: ByteArray): ByteArray = joinBytes(u32be((payload.size + BOX_HEADER_BYTES).toLong()), asciiBytes(type), payload)

fun ftypBox(major: String, compatible: List<String>): ByteArray =
    isoBox("ftyp", joinBytes(asciiBytes(major), u32be(0), *compatible.map(::asciiBytes).toTypedArray()))

fun mvhdBox(creationSeconds1904: Long): ByteArray =
    isoBox("mvhd", joinBytes(byteArrayOf(0, 0, 0, 0), u32be(creationSeconds1904), ByteArray(MVHD_V0_PAYLOAD_BYTES - 8)))

fun tinyMp4(): ByteArray = joinBytes(ftypBox("isom", listOf("isom")), isoBox("moov", mvhdBox(0)), isoBox("mdat", seededBytes(3000, 8)))
