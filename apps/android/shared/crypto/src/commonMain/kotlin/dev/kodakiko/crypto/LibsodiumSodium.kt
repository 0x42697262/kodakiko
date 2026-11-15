package dev.kodakiko.crypto

internal class LibsodiumSodium(private val native: SodiumNative) : Sodium {
    override fun randomBytes(length: Int): ByteArray {
        require(length >= 0)
        return ByteArray(length).also { native.randomBytes(it) }
    }

    override fun argon2id(input: Argon2idInput): ByteArray {
        requireLength(input.salt, SALT_BYTES)
        if (input.outputLength < ARGON2ID_OUTPUT_BYTES_MIN) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
        require(input.opsLimit >= ARGON2ID_OPS_LIMIT_MIN && input.memLimitBytes > 0)
        val output = ByteArray(input.outputLength)
        val result = native.pwhash(output, input.password, input.salt, input.opsLimit, input.memLimitBytes, ARGON2ID13_ALGORITHM)
        return succeeded(result, output)
    }

    override fun kdfDerive(subkeyLength: Int, subkeyId: Long, context: String, key: ByteArray): ByteArray {
        requireLength(key, KDF_KEY_BYTES)
        if (subkeyLength !in KDF_SUBKEY_BYTES_MIN..KDF_SUBKEY_BYTES_MAX) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
        val contextBytes = utf8Encode(context)
        require(contextBytes.size == KDF_CONTEXT_BYTES && subkeyId >= 0)
        val output = ByteArray(subkeyLength)
        return succeeded(native.kdfDeriveFromKey(output, subkeyId, contextBytes, key), output)
    }

    override fun aeadEncrypt(plaintext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        requireLength(key, KEY_BYTES)
        requireLength(nonce, AEAD_NONCE_BYTES)
        val output = ByteArray(plaintext.size + AEAD_TAG_BYTES)
        return succeeded(native.aeadEncrypt(output, plaintext, additionalData, nonce, key), output)
    }

    override fun aeadDecrypt(ciphertext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        if (key.size != KEY_BYTES || nonce.size != AEAD_NONCE_BYTES || ciphertext.size < AEAD_TAG_BYTES) {
            throw DecryptionError(CryptoText.DECRYPTION_FAILED)
        }
        val output = ByteArray(ciphertext.size - AEAD_TAG_BYTES)
        return opened(native.aeadDecrypt(output, ciphertext, additionalData, nonce, key), output)
    }

    override fun boxKeypair(): BoxKeypair {
        val publicKey = ByteArray(BOX_PUBLIC_KEY_BYTES)
        val privateKey = ByteArray(BOX_SECRET_KEY_BYTES)
        return keypair(native.boxKeypair(publicKey, privateKey), publicKey, privateKey)
    }

    override fun boxSeedKeypair(seed: ByteArray): BoxKeypair {
        requireLength(seed, BOX_SEED_BYTES)
        val publicKey = ByteArray(BOX_PUBLIC_KEY_BYTES)
        val privateKey = ByteArray(BOX_SECRET_KEY_BYTES)
        return keypair(native.boxSeedKeypair(publicKey, privateKey, seed), publicKey, privateKey)
    }

    override fun boxSeal(message: ByteArray, publicKey: ByteArray): ByteArray {
        requireLength(publicKey, BOX_PUBLIC_KEY_BYTES)
        val output = ByteArray(message.size + BOX_SEAL_BYTES)
        return succeeded(native.boxSeal(output, message, publicKey), output)
    }

    override fun boxSealOpen(sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray {
        if (publicKey.size != BOX_PUBLIC_KEY_BYTES || privateKey.size != BOX_SECRET_KEY_BYTES || sealed.size < BOX_SEAL_BYTES) {
            throw DecryptionError(CryptoText.DECRYPTION_FAILED)
        }
        val output = ByteArray(sealed.size - BOX_SEAL_BYTES)
        return opened(native.boxSealOpen(output, sealed, publicKey, privateKey), output)
    }

    override fun genericHash(outputLength: Int, input: ByteArray): ByteArray {
        if (outputLength !in GENERIC_HASH_BYTES_MIN..GENERIC_HASH_BYTES_MAX) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
        val output = ByteArray(outputLength)
        return succeeded(native.genericHash(output, input), output)
    }

    override fun libraryVersion(): String = native.versionString()

    private fun requireLength(bytes: ByteArray, length: Int) {
        if (bytes.size != length) throw InvalidKeyLengthError(CryptoText.INVALID_KEY_LENGTH)
    }

    private fun succeeded(result: Int, output: ByteArray): ByteArray {
        if (result != SODIUM_SUCCESS) {
            wipe(output)
            error(CryptoText.SODIUM_CALL_FAILED)
        }
        return output
    }

    private fun opened(result: Int, output: ByteArray): ByteArray {
        if (result != SODIUM_SUCCESS) {
            wipe(output)
            throw DecryptionError(CryptoText.DECRYPTION_FAILED)
        }
        return output
    }

    private fun keypair(result: Int, publicKey: ByteArray, privateKey: ByteArray): BoxKeypair {
        if (result != SODIUM_SUCCESS) {
            wipe(privateKey)
            error(CryptoText.SODIUM_CALL_FAILED)
        }
        return BoxKeypair(publicKey, privateKey)
    }
}
