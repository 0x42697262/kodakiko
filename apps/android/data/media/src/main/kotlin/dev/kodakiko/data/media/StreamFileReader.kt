package dev.kodakiko.data.media

import dev.kodakiko.crypto.MalformedObjectError
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.StreamLayout
import dev.kodakiko.crypto.TruncatedObjectError
import dev.kodakiko.crypto.decryptChunk
import dev.kodakiko.crypto.parseStreamHeader
import dev.kodakiko.crypto.streamLayout
import java.io.Closeable

class StreamFileReader private constructor(
    private val sodium: Sodium,
    private val source: CiphertextSource,
    private val variantKey: ByteArray,
    private val header: ByteArray,
    private val layout: StreamLayout,
) : Closeable {
    private val cache = LinkedHashMap<Long, ByteArray>()
    private var closed = false

    val size: Long get() = layout.plaintextSize

    @Synchronized
    fun read(offset: Long, length: Int): ByteArray {
        check(!closed) { MediaText.READER_CLOSED }
        val start = maxOf(0L, offset)
        if (length <= 0 || start >= size) return ByteArray(0)
        val end = start + minOf(length.toLong(), size - start)
        val output = ByteArray((end - start).toInt())
        val chunkSize = layout.chunkSize.toLong()
        var index = start / chunkSize
        while (index * chunkSize < end) {
            val plaintext = chunk(index)
            val base = index * chunkSize
            val from = maxOf(start, base)
            val to = minOf(end, base + plaintext.size)
            plaintext.copyInto(output, (from - start).toInt(), (from - base).toInt(), (to - base).toInt())
            index += 1
        }
        return output
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cache.values.forEach { it.fill(0) }
        cache.clear()
        variantKey.fill(0)
        source.close()
    }

    private fun chunk(index: Long): ByteArray {
        cache.remove(index)?.let { cached ->
            cache[index] = cached
            return cached
        }
        val plaintext = decrypt(index)
        cache[index] = plaintext
        while (cache.size > MediaConstants.STREAM_CHUNK_CACHE_ITEMS) {
            val eldest = cache.keys.first()
            cache.remove(eldest)?.fill(0)
        }
        return plaintext
    }

    private fun decrypt(index: Long): ByteArray {
        val offset = STREAM_HEADER_BYTES + index * layout.chunkCiphertextSize
        val length = minOf(layout.chunkCiphertextSize.toLong(), layout.ciphertextSize - offset).toInt()
        val ciphertext = source.read(offset, length)
        if (ciphertext.size != length) throw TruncatedObjectError()
        return decryptChunk(sodium, variantKey, header, index, index == layout.chunkCount - 1, ciphertext)
    }

    private fun proveFinalChunk() {
        decrypt(layout.chunkCount - 1).fill(0)
    }

    companion object {
        fun open(sodium: Sodium, source: CiphertextSource, variantKey: ByteArray, plaintextSize: Long): StreamFileReader {
            val key = variantKey.copyOf()
            try {
                val header = source.read(0, STREAM_HEADER_BYTES)
                if (header.size != STREAM_HEADER_BYTES) throw TruncatedObjectError()
                val layout = streamLayout(plaintextSize, parseStreamHeader(header).chunkSizeExponent)
                val actualSize = source.size
                if (actualSize < layout.ciphertextSize) throw TruncatedObjectError()
                if (actualSize > layout.ciphertextSize) throw MalformedObjectError()
                return StreamFileReader(sodium, source, key, header, layout).also { it.proveFinalChunk() }
            } catch (error: Throwable) {
                key.fill(0)
                source.close()
                throw error
            }
        }
    }
}
