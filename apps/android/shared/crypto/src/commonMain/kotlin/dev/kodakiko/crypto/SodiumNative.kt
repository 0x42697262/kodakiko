package dev.kodakiko.crypto

internal interface SodiumNative {
    fun randomBytes(target: ByteArray)

    fun pwhash(output: ByteArray, password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long, algorithm: Int): Int

    fun kdfDeriveFromKey(output: ByteArray, subkeyId: Long, context: ByteArray, key: ByteArray): Int

    fun aeadEncrypt(output: ByteArray, plaintext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): Int

    fun aeadDecrypt(output: ByteArray, ciphertext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): Int

    fun boxKeypair(publicKey: ByteArray, privateKey: ByteArray): Int

    fun boxSeedKeypair(publicKey: ByteArray, privateKey: ByteArray, seed: ByteArray): Int

    fun boxSeal(output: ByteArray, message: ByteArray, publicKey: ByteArray): Int

    fun boxSealOpen(output: ByteArray, sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): Int

    fun genericHash(output: ByteArray, input: ByteArray): Int

    fun versionString(): String
}
