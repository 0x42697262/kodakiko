package dev.kodakiko.crypto

class StreamHeader(val chunkSizeExponent: Int, noncePrefix: ByteArray) {
    private val prefix: ByteArray = noncePrefix.copyOf()

    val noncePrefix: ByteArray get() = prefix.copyOf()

    internal fun writePrefix(target: ByteArray) {
        prefix.copyInto(target)
    }
}

data class StreamLayout(
    val plaintextSize: Long,
    val chunkSizeExponent: Int,
    val chunkSize: Int,
    val chunkCount: Long,
    val chunkCiphertextSize: Int,
    val ciphertextSize: Long,
)

data class ChunkRange(val firstChunk: Long, val lastChunk: Long)

interface StreamEncryptor {
    val header: ByteArray
    val layout: StreamLayout

    fun encryptNext(chunk: ByteArray): ByteArray

    fun isDone(): Boolean
}

interface StreamDecryptor {
    val layout: StreamLayout

    fun decryptNext(chunk: ByteArray): ByteArray

    fun finish()
}

fun createStreamHeader(sodium: Sodium): ByteArray = concatBytes(
    STREAM_MAGIC,
    byteArrayOf(STREAM_VERSION.toByte(), STREAM_CHUNK_SIZE_EXPONENT.toByte()),
    ByteArray(STREAM_HEADER_RESERVED_BYTES),
    sodium.randomBytes(STREAM_NONCE_PREFIX_BYTES),
)

fun parseStreamHeader(bytes: ByteArray): StreamHeader {
    val magic = STREAM_MAGIC
    if (bytes.size != STREAM_HEADER_BYTES || !bytesEqual(bytes.copyOfRange(STREAM_HEADER_MAGIC_OFFSET, STREAM_HEADER_MAGIC_OFFSET + magic.size), magic)) {
        throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    }
    if (bytes[STREAM_HEADER_VERSION_OFFSET].toInt() != STREAM_VERSION) throw UnsupportedFormatError(CryptoText.UNSUPPORTED_FORMAT)
    val reserved = bytes.copyOfRange(STREAM_HEADER_RESERVED_OFFSET, STREAM_HEADER_RESERVED_OFFSET + STREAM_HEADER_RESERVED_BYTES)
    if (reserved.any { it != 0.toByte() }) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    val chunkSizeExponent = bytes[STREAM_HEADER_EXPONENT_OFFSET].toInt() and BYTE_MASK
    requireChunkSizeExponent(chunkSizeExponent)
    return StreamHeader(
        chunkSizeExponent,
        bytes.copyOfRange(STREAM_HEADER_PREFIX_OFFSET, STREAM_HEADER_PREFIX_OFFSET + STREAM_NONCE_PREFIX_BYTES),
    )
}

private fun requireChunkSizeExponent(chunkSizeExponent: Int) {
    if (chunkSizeExponent !in STREAM_CHUNK_SIZE_EXPONENT_MIN..STREAM_CHUNK_SIZE_EXPONENT_MAX) {
        throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    }
}

private fun requireVariantKeyLength(variantKey: ByteArray) {
    if (variantKey.size != KEY_BYTES) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
}

private fun chunkSizeOf(chunkSizeExponent: Int): Int = 1 shl chunkSizeExponent

private fun ceilDivide(dividend: Long, divisor: Long): Long = (dividend + divisor - 1) / divisor

fun streamLayout(plaintextSize: Long, chunkSizeExponent: Int): StreamLayout {
    if (plaintextSize !in 0..STREAM_MAX_CHUNK_INDEX) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    requireChunkSizeExponent(chunkSizeExponent)
    val chunkSize = chunkSizeOf(chunkSizeExponent)
    val chunkCount = maxOf(1L, ceilDivide(plaintextSize, chunkSize.toLong()))
    return StreamLayout(
        plaintextSize = plaintextSize,
        chunkSizeExponent = chunkSizeExponent,
        chunkSize = chunkSize,
        chunkCount = chunkCount,
        chunkCiphertextSize = chunkSize + AEAD_TAG_BYTES,
        ciphertextSize = STREAM_HEADER_BYTES + plaintextSize + AEAD_TAG_BYTES * chunkCount,
    )
}

fun layoutFromCiphertextSize(ciphertextSize: Long, chunkSizeExponent: Int): StreamLayout {
    requireChunkSizeExponent(chunkSizeExponent)
    if (ciphertextSize < STREAM_MIN_OBJECT_BYTES) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    val chunkCiphertextSize = chunkSizeOf(chunkSizeExponent) + AEAD_TAG_BYTES
    val chunkCount = ceilDivide(ciphertextSize - STREAM_HEADER_BYTES, chunkCiphertextSize.toLong())
    val plaintextSize = ciphertextSize - STREAM_HEADER_BYTES - AEAD_TAG_BYTES * chunkCount
    if (chunkCount < 1 || plaintextSize < 0) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    val layout = streamLayout(plaintextSize, chunkSizeExponent)
    if (layout.ciphertextSize != ciphertextSize) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    return layout
}

fun chunkRangeForPlaintextRange(layout: StreamLayout, start: Long, endInclusive: Long): ChunkRange {
    if (start < 0 || start > endInclusive || endInclusive >= layout.plaintextSize) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    return ChunkRange(firstChunk = start / layout.chunkSize, lastChunk = endInclusive / layout.chunkSize)
}

fun streamNonce(header: StreamHeader, index: Long, isFinal: Boolean): ByteArray {
    val nonce = ByteArray(AEAD_NONCE_BYTES)
    header.writePrefix(nonce)
    writeUint56BigEndian(nonce, STREAM_NONCE_PREFIX_BYTES, index)
    nonce[STREAM_NONCE_PREFIX_BYTES + STREAM_COUNTER_BYTES] = (if (isFinal) STREAM_FINAL_FLAG else STREAM_NOT_FINAL_FLAG).toByte()
    return nonce
}

fun streamAdditionalData(header: ByteArray): ByteArray = utf8Encode(bytesToHex(header))

fun encryptChunk(sodium: Sodium, variantKey: ByteArray, header: ByteArray, index: Long, isFinal: Boolean, plaintext: ByteArray): ByteArray {
    requireVariantKeyLength(variantKey)
    val parsed = parseStreamHeader(header)
    val chunkSize = chunkSizeOf(parsed.chunkSizeExponent)
    if (if (isFinal) plaintext.size > chunkSize else plaintext.size != chunkSize) throw InvalidChunkError(CryptoText.INVALID_CHUNK)
    return sodium.aeadEncrypt(plaintext, streamAdditionalData(header), streamNonce(parsed, index, isFinal), variantKey)
}

fun decryptChunk(sodium: Sodium, variantKey: ByteArray, header: ByteArray, index: Long, isFinal: Boolean, ciphertext: ByteArray): ByteArray {
    requireVariantKeyLength(variantKey)
    val parsed = parseStreamHeader(header)
    val chunkSize = chunkSizeOf(parsed.chunkSizeExponent)
    if (ciphertext.size < AEAD_TAG_BYTES || ciphertext.size > chunkSize + AEAD_TAG_BYTES) throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
    return sodium.aeadDecrypt(ciphertext, streamAdditionalData(header), streamNonce(parsed, index, isFinal), variantKey)
}

private fun chunkPlaintextSize(layout: StreamLayout, index: Long): Long = if (index == layout.chunkCount - 1) {
    layout.plaintextSize - layout.chunkSize.toLong() * (layout.chunkCount - 1)
} else {
    layout.chunkSize.toLong()
}

fun createStreamEncryptor(sodium: Sodium, variantKey: ByteArray, plaintextSize: Long): StreamEncryptor {
    val privateHeader = createStreamHeader(sodium)
    val objectLayout = streamLayout(plaintextSize, parseStreamHeader(privateHeader).chunkSizeExponent)
    return object : StreamEncryptor {
        private var nextIndex = 0L

        override val header: ByteArray get() = privateHeader.copyOf()

        override val layout: StreamLayout = objectLayout

        override fun encryptNext(chunk: ByteArray): ByteArray {
            if (nextIndex >= layout.chunkCount || chunk.size.toLong() != chunkPlaintextSize(layout, nextIndex)) {
                throw InvalidChunkError(CryptoText.INVALID_CHUNK)
            }
            val ciphertext = encryptChunk(sodium, variantKey, privateHeader, nextIndex, nextIndex == layout.chunkCount - 1, chunk)
            nextIndex += 1
            return ciphertext
        }

        override fun isDone(): Boolean = nextIndex >= layout.chunkCount
    }
}

fun createStreamDecryptor(sodium: Sodium, variantKey: ByteArray, header: ByteArray, expectedPlaintextSize: Long): StreamDecryptor {
    val privateHeader = header.copyOf()
    val objectLayout = streamLayout(expectedPlaintextSize, parseStreamHeader(privateHeader).chunkSizeExponent)
    return object : StreamDecryptor {
        private var nextIndex = 0L

        override val layout: StreamLayout = objectLayout

        override fun decryptNext(chunk: ByteArray): ByteArray {
            if (nextIndex >= layout.chunkCount) throw InvalidChunkError(CryptoText.INVALID_CHUNK)
            if (chunk.size.toLong() != chunkPlaintextSize(layout, nextIndex) + AEAD_TAG_BYTES) {
                throw MalformedObjectError(CryptoText.MALFORMED_OBJECT)
            }
            val plaintext = decryptChunk(sodium, variantKey, privateHeader, nextIndex, nextIndex == layout.chunkCount - 1, chunk)
            nextIndex += 1
            return plaintext
        }

        override fun finish() {
            if (nextIndex < layout.chunkCount) throw TruncatedObjectError(CryptoText.TRUNCATED_OBJECT)
        }
    }
}
