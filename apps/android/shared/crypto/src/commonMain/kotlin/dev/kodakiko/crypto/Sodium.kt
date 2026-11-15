package dev.kodakiko.crypto

class Argon2idInput(
    val password: ByteArray,
    val salt: ByteArray,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val outputLength: Int,
)

class BoxKeypair(val publicKey: ByteArray, val privateKey: ByteArray)

interface Sodium {
    fun randomBytes(length: Int): ByteArray

    fun argon2id(input: Argon2idInput): ByteArray

    fun kdfDerive(subkeyLength: Int, subkeyId: Long, context: String, key: ByteArray): ByteArray

    fun aeadEncrypt(plaintext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    fun aeadDecrypt(ciphertext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    fun boxKeypair(): BoxKeypair

    fun boxSeedKeypair(seed: ByteArray): BoxKeypair

    fun boxSeal(message: ByteArray, publicKey: ByteArray): ByteArray

    fun boxSealOpen(sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray

    fun genericHash(outputLength: Int, input: ByteArray): ByteArray

    fun libraryVersion(): String
}

expect fun createSodium(): Sodium
