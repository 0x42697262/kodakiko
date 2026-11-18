package dev.kodakiko.service.entries

import dev.kodakiko.crypto.AEAD_TAG_BYTES
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_BASE
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import kotlin.math.pow

fun maxPlaintextBytes(maxCiphertextBytes: Long): Long {
    val chunkSize = STREAM_CHUNK_SIZE_BASE.toDouble().pow(STREAM_CHUNK_SIZE_EXPONENT).toLong()
    val body = maxCiphertextBytes - STREAM_HEADER_BYTES
    if (body <= 0) return 0
    val fullChunks = body / (chunkSize + AEAD_TAG_BYTES)
    val rest = body - fullChunks * (chunkSize + AEAD_TAG_BYTES)
    return fullChunks * chunkSize + maxOf(0L, rest - AEAD_TAG_BYTES)
}
