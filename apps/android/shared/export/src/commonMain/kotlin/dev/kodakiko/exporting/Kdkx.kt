package dev.kodakiko.exporting

import dev.kodakiko.crypto.AEAD_TAG_BYTES
import dev.kodakiko.crypto.Argon2idInput
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.MalformedObjectError
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.concatBytes
import dev.kodakiko.crypto.createStreamHeader
import dev.kodakiko.crypto.decryptChunk
import dev.kodakiko.crypto.encryptChunk
import dev.kodakiko.crypto.utf8Encode
import dev.kodakiko.crypto.wipe
import dev.kodakiko.exporting.KdkxConstants.BYTE_BITS
import dev.kodakiko.exporting.KdkxConstants.BYTE_MASK
import dev.kodakiko.exporting.KdkxConstants.MEM_OFFSET
import dev.kodakiko.exporting.KdkxConstants.OPS_OFFSET
import dev.kodakiko.exporting.KdkxConstants.PREFIX_BYTES
import dev.kodakiko.exporting.KdkxConstants.SALT_OFFSET
import dev.kodakiko.exporting.KdkxConstants.U32_BYTES
import dev.kodakiko.exporting.KdkxConstants.VERSION
import dev.kodakiko.exporting.KdkxConstants.VERSION_OFFSET

class KdkxParams(salt: ByteArray, val opsLimit: Long, val memLimitBytes: Long) {
    private val saltBytes: ByteArray = salt.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()
}

interface KdkxWriter {
    val header: ByteArray

    fun push(bytes: ByteArray): List<ByteArray>

    fun finish(): ByteArray
}

private val magic: ByteArray get() = utf8Encode(KdkxConstants.MAGIC_TEXT)

private fun malformed(): Nothing = throw MalformedObjectError(ExportText.KDKX_MALFORMED)

fun isKdkx(bytes: ByteArray): Boolean {
    val expected = magic
    return bytes.size >= expected.size && expected.indices.all { bytes[it] == expected[it] }
}

fun deriveKdkxKey(sodium: Sodium, passphrase: String, params: KdkxParams): ByteArray {
    val password = utf8Encode(passphrase)
    try {
        return sodium.argon2id(Argon2idInput(password, params.salt, params.opsLimit, params.memLimitBytes, KEY_BYTES))
    } finally {
        wipe(password)
    }
}

private fun writeUint32(target: ByteArray, offset: Int, value: Long) {
    val unsigned = value and KdkxConstants.U32_MASK
    for (index in 0 until U32_BYTES) {
        target[offset + index] = ((unsigned shr (BYTE_BITS * (U32_BYTES - 1 - index))) and BYTE_MASK.toLong()).toByte()
    }
}

private fun readUint32(source: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until U32_BYTES) value = (value shl BYTE_BITS) or (source[offset + index].toLong() and BYTE_MASK.toLong())
    return value
}

private fun prefix(params: KdkxParams): ByteArray {
    val out = ByteArray(PREFIX_BYTES)
    magic.copyInto(out)
    out[VERSION_OFFSET] = VERSION.toByte()
    writeUint32(out, OPS_OFFSET, params.opsLimit)
    writeUint32(out, MEM_OFFSET, params.memLimitBytes)
    params.salt.copyInto(out, SALT_OFFSET)
    return out
}

fun readKdkxParams(file: ByteArray): KdkxParams {
    if (!isKdkx(file) || file.size < PREFIX_BYTES + STREAM_HEADER_BYTES + AEAD_TAG_BYTES || file[VERSION_OFFSET].toInt() != VERSION) {
        malformed()
    }
    return KdkxParams(
        salt = file.copyOfRange(SALT_OFFSET, SALT_OFFSET + KDKX_SALT_BYTES),
        opsLimit = readUint32(file, OPS_OFFSET),
        memLimitBytes = readUint32(file, MEM_OFFSET),
    )
}

private class StreamingKdkxWriter(private val sodium: Sodium, private val key: ByteArray, params: KdkxParams) : KdkxWriter {
    private val streamHeader = createStreamHeader(sodium)
    private val fileHeader = concatBytes(prefix(params), streamHeader)
    private val pending = ByteArray(KDKX_CHUNK_BYTES)
    private var pendingSize = 0
    private var index = 0L
    private var done = false

    override val header: ByteArray get() = fileHeader.copyOf()

    override fun push(bytes: ByteArray): List<ByteArray> {
        if (done) malformed()
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            if (pendingSize == KDKX_CHUNK_BYTES) {
                out.add(encryptChunk(sodium, key, streamHeader, index, false, pending))
                pendingSize = 0
                index += 1
            }
            val count = minOf(KDKX_CHUNK_BYTES - pendingSize, bytes.size - offset)
            bytes.copyInto(pending, pendingSize, offset, offset + count)
            pendingSize += count
            offset += count
        }
        return out
    }

    override fun finish(): ByteArray {
        if (done) malformed()
        done = true
        val last = pending.copyOf(pendingSize)
        try {
            return encryptChunk(sodium, key, streamHeader, index, true, last)
        } finally {
            wipe(last)
            wipe(pending)
        }
    }
}

fun createKdkxWriter(sodium: Sodium, key: ByteArray, params: KdkxParams): KdkxWriter = StreamingKdkxWriter(sodium, key, params)

fun decryptKdkx(sodium: Sodium, key: ByteArray, file: ByteArray): ByteArray {
    readKdkxParams(file)
    val streamHeader = file.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + STREAM_HEADER_BYTES)
    val parts = mutableListOf<ByteArray>()
    val fullChunk = KDKX_CHUNK_BYTES + AEAD_TAG_BYTES
    var offset = PREFIX_BYTES + STREAM_HEADER_BYTES
    var index = 0L
    while (file.size - offset > fullChunk) {
        parts.add(decryptChunk(sodium, key, streamHeader, index, false, file.copyOfRange(offset, offset + fullChunk)))
        offset += fullChunk
        index += 1
    }
    if (file.size - offset < AEAD_TAG_BYTES) malformed()
    parts.add(decryptChunk(sodium, key, streamHeader, index, true, file.copyOfRange(offset, file.size)))
    return concatBytes(*parts.toTypedArray())
}
