package dev.kodakiko.crypto

fun buildSharePayload(collectionId: String, collectionKey: ByteArray): ByteArray {
    if (!ID_PATTERN.matches(collectionId)) throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    if (collectionKey.size != KEY_BYTES) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
    requireNonZeroKey(collectionKey)
    val idBytes = utf8Encode(collectionId)
    return concatBytes(byteArrayOf(FORMAT_VERSION_SHARE_PAYLOAD.toByte(), idBytes.size.toByte()), idBytes, collectionKey)
}

fun openSharePayload(sodium: Sodium, sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray, expectedCollectionId: String): ByteArray {
    val payload = sodium.boxSealOpen(sealed, publicKey, privateKey)
    try {
        if (payload.isEmpty() || payload[0].toInt() != FORMAT_VERSION_SHARE_PAYLOAD) {
            throw UnsupportedFormatError(CryptoText.UNSUPPORTED_FORMAT)
        }
        val idLength = payload.getOrNull(SHARE_PAYLOAD_ID_LENGTH_OFFSET)?.let { it.toInt() and BYTE_MASK }
        if (idLength == null || payload.size != SHARE_PAYLOAD_ID_OFFSET + idLength + KEY_BYTES) {
            throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
        }
        val embeddedId = payload.copyOfRange(SHARE_PAYLOAD_ID_OFFSET, SHARE_PAYLOAD_ID_OFFSET + idLength)
        if (!bytesEqual(embeddedId, utf8Encode(expectedCollectionId))) {
            throw DecryptionError(CryptoText.SHARE_COLLECTION_MISMATCH)
        }
        return payload.copyOfRange(SHARE_PAYLOAD_ID_OFFSET + idLength, payload.size)
    } finally {
        wipe(payload)
    }
}

fun publicKeyFingerprint(sodium: Sodium, publicKey: ByteArray): String {
    val hex = bytesToHex(sodium.genericHash(GENERIC_HASH_BYTES, publicKey).copyOfRange(0, FINGERPRINT_BYTES))
    return (0 until FINGERPRINT_GROUPS).joinToString(FINGERPRINT_SEPARATOR) { group ->
        val start = group * FINGERPRINT_GROUP_CHARS
        hex.substring(start, start + FINGERPRINT_GROUP_CHARS)
    }
}
