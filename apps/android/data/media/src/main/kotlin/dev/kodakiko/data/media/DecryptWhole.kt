package dev.kodakiko.data.media

import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createStreamDecryptor

fun decryptWhole(sodium: Sodium, variantKey: ByteArray, ciphertext: ByteArray, plaintextSize: Long): ByteArray {
    val header = ciphertext.copyOfRange(0, minOf(STREAM_HEADER_BYTES, ciphertext.size))
    val decryptor = createStreamDecryptor(sodium, variantKey, header, plaintextSize)
    val layout = decryptor.layout
    val output = ByteArray(Math.toIntExact(layout.plaintextSize))
    try {
        var position = 0
        for (index in 0 until layout.chunkCount) {
            val start = minOf(STREAM_HEADER_BYTES + index * layout.chunkCiphertextSize, ciphertext.size.toLong()).toInt()
            val end = minOf(start.toLong() + layout.chunkCiphertextSize, ciphertext.size.toLong()).toInt()
            val plaintext = decryptor.decryptNext(ciphertext.copyOfRange(start, end))
            plaintext.copyInto(output, position)
            position += plaintext.size
            plaintext.fill(0)
        }
        decryptor.finish()
        return output
    } catch (failure: Throwable) {
        output.fill(0)
        throw failure
    }
}
