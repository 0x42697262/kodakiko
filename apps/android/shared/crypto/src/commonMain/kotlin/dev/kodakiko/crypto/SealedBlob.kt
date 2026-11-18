package dev.kodakiko.crypto

private fun requireKeyLength(key: ByteArray) {
    if (key.size != KEY_BYTES) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
    requireNonZeroKey(key)
}

fun buildAdditionalData(context: AdContext, bindingId: String): ByteArray =
    concatBytes(utf8Encode(context.value), byteArrayOf(AD_SEPARATOR.toByte()), utf8Encode(bindingId))

fun sealBlob(sodium: Sodium, key: ByteArray, plaintext: ByteArray, context: AdContext, bindingId: String): ByteArray {
    requireKeyLength(key)
    val nonce = sodium.randomBytes(AEAD_NONCE_BYTES)
    val ciphertext = sodium.aeadEncrypt(plaintext, buildAdditionalData(context, bindingId), nonce, key)
    return concatBytes(byteArrayOf(FORMAT_VERSION_SEALED_BLOB.toByte()), nonce, ciphertext)
}

fun openBlob(sodium: Sodium, key: ByteArray, blob: ByteArray, context: AdContext, bindingId: String): ByteArray {
    if (key.size == KEY_BYTES) requireNonZeroKey(key)
    if (blob.size < BLOB_VERSION_BYTES + AEAD_NONCE_BYTES + AEAD_TAG_BYTES) throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    if (blob[BLOB_VERSION_OFFSET] != FORMAT_VERSION_SEALED_BLOB.toByte()) throw UnsupportedFormatError(CryptoText.UNSUPPORTED_FORMAT)
    val nonce = blob.copyOfRange(BLOB_VERSION_BYTES, BLOB_VERSION_BYTES + AEAD_NONCE_BYTES)
    val ciphertext = blob.copyOfRange(BLOB_VERSION_BYTES + AEAD_NONCE_BYTES, blob.size)
    return sodium.aeadDecrypt(ciphertext, buildAdditionalData(context, bindingId), nonce, key)
}

fun wrapKey(sodium: Sodium, wrappingKey: ByteArray, keyToWrap: ByteArray, context: AdContext, bindingId: String): ByteArray {
    requireKeyLength(wrappingKey)
    requireKeyLength(keyToWrap)
    return sealBlob(sodium, wrappingKey, keyToWrap, context, bindingId)
}

fun unwrapKey(sodium: Sodium, wrappingKey: ByteArray, wrapped: ByteArray, context: AdContext, bindingId: String): ByteArray {
    requireKeyLength(wrappingKey)
    val key = openBlob(sodium, wrappingKey, wrapped, context, bindingId)
    if (key.size != KEY_BYTES) {
        wipe(key)
        throw MalformedPayloadError(CryptoText.MALFORMED_PAYLOAD)
    }
    return key
}
