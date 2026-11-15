package dev.kodakiko.crypto

fun padIso7816(bytes: ByteArray, blockBytes: Int): ByteArray {
    val paddedLength = (bytes.size / blockBytes + 1) * blockBytes
    val padded = ByteArray(paddedLength)
    bytes.copyInto(padded)
    padded[bytes.size] = PADDING_MARKER.toByte()
    return padded
}

fun unpadIso7816(bytes: ByteArray, blockBytes: Int): ByteArray {
    if (bytes.isEmpty() || bytes.size % blockBytes != 0) throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    var end = bytes.size
    while (end > 0 && bytes[end - 1] == 0.toByte()) {
        end -= 1
    }
    if (end == 0 || bytes[end - 1] != PADDING_MARKER.toByte()) throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    return bytes.copyOfRange(0, end - 1)
}
