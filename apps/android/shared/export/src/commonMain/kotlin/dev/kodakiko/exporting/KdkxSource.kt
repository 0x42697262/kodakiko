package dev.kodakiko.exporting

import dev.kodakiko.crypto.AEAD_TAG_BYTES
import dev.kodakiko.crypto.MalformedObjectError
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.decryptChunk
import dev.kodakiko.crypto.wipe
import dev.kodakiko.exporting.KdkxConstants.BODY_OFFSET
import dev.kodakiko.exporting.KdkxConstants.FULL_CHUNK_BYTES
import dev.kodakiko.exporting.KdkxConstants.NO_CHUNK
import dev.kodakiko.exporting.KdkxConstants.PREFIX_BYTES

private fun malformedKdkx(): Nothing = throw MalformedObjectError(ExportText.KDKX_MALFORMED)

fun readKdkxParams(source: RandomSource): KdkxParams =
    readKdkxParams(source.read(0, minOf(source.size, (BODY_OFFSET + AEAD_TAG_BYTES).toLong()).toInt()))

private class KdkxSource(private val sodium: Sodium, key: ByteArray, private val cipher: RandomSource) : RandomSource {
    private val key: ByteArray
    private val streamHeader: ByteArray
    private val lastIndex: Long
    private val lastChunkBytes: Int
    private var cachedIndex = NO_CHUNK
    private var cached = ByteArray(0)
    private var closed = false

    override val size: Long

    init {
        readKdkxParams(cipher)
        streamHeader = cipher.read(PREFIX_BYTES.toLong(), STREAM_HEADER_BYTES)
        val body = cipher.size - BODY_OFFSET
        lastIndex = if (body <= FULL_CHUNK_BYTES) 0 else (body - 1) / FULL_CHUNK_BYTES
        val last = body - lastIndex * FULL_CHUNK_BYTES
        if (last < AEAD_TAG_BYTES) malformedKdkx()
        lastChunkBytes = last.toInt()
        size = body - (lastIndex + 1) * AEAD_TAG_BYTES
        this.key = key.copyOf()
    }

    private fun chunk(index: Long): ByteArray {
        if (closed) malformedKdkx()
        if (index == cachedIndex) return cached
        val final = index == lastIndex
        val ciphertext = cipher.read(BODY_OFFSET + index * FULL_CHUNK_BYTES, if (final) lastChunkBytes else FULL_CHUNK_BYTES)
        val plain = decryptChunk(sodium, key, streamHeader, index, final, ciphertext)
        wipe(cached)
        cached = plain
        cachedIndex = index
        return plain
    }

    fun check() {
        chunk(0)
        chunk(lastIndex)
    }

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > size) malformedKdkx()
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val at = position + done
            val plain = chunk(at / KDKX_CHUNK_BYTES)
            val within = (at % KDKX_CHUNK_BYTES).toInt()
            val count = minOf(length - done, plain.size - within)
            if (count <= 0) malformedKdkx()
            plain.copyInto(out, done, within, within + count)
            done += count
        }
        return out
    }

    override fun close() {
        if (closed) return
        closed = true
        wipe(key)
        wipe(cached)
        cachedIndex = NO_CHUNK
    }
}

fun openKdkx(sodium: Sodium, key: ByteArray, cipher: RandomSource): RandomSource {
    val source = KdkxSource(sodium, key, cipher)
    try {
        source.check()
    } catch (failure: Exception) {
        source.close()
        throw failure
    }
    return source
}
