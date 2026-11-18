package dev.kodakiko.crypto

import com.goterl.lazysodium.SodiumAndroid
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong

private val sodium: Sodium by lazy { LibsodiumSodium(LazysodiumNative(SodiumAndroid())) }

actual fun createSodium(): Sodium = sodium

private class LazysodiumNative(private val library: SodiumAndroid) : SodiumNative {
    private val version: String by lazy {
        NativeLibrary.getInstance(SODIUM_LIBRARY_NAME).getFunction(SODIUM_VERSION_SYMBOL).invokeString(arrayOf(), false)
    }

    override fun randomBytes(target: ByteArray) {
        library.randombytes_buf(target, target.size)
    }

    override fun pwhash(output: ByteArray, password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long, algorithm: Int): Int =
        library.crypto_pwhash(output, output.size.toLong(), password, password.size.toLong(), salt, opsLimit, NativeLong(memLimitBytes), algorithm)

    override fun kdfDeriveFromKey(output: ByteArray, subkeyId: Long, context: ByteArray, key: ByteArray): Int =
        library.crypto_kdf_derive_from_key(output, output.size, subkeyId, context, key)

    override fun aeadEncrypt(output: ByteArray, plaintext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): Int =
        library.crypto_aead_xchacha20poly1305_ietf_encrypt(
            output,
            null,
            plaintext,
            plaintext.size.toLong(),
            additionalData,
            additionalData.size.toLong(),
            null,
            nonce,
            key,
        )

    override fun aeadDecrypt(output: ByteArray, ciphertext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): Int =
        library.crypto_aead_xchacha20poly1305_ietf_decrypt(
            output,
            null,
            null,
            ciphertext,
            ciphertext.size.toLong(),
            additionalData,
            additionalData.size.toLong(),
            nonce,
            key,
        )

    override fun boxKeypair(publicKey: ByteArray, privateKey: ByteArray): Int = library.crypto_box_keypair(publicKey, privateKey)

    override fun boxSeedKeypair(publicKey: ByteArray, privateKey: ByteArray, seed: ByteArray): Int =
        library.crypto_box_seed_keypair(publicKey, privateKey, seed)

    override fun boxSeal(output: ByteArray, message: ByteArray, publicKey: ByteArray): Int =
        library.crypto_box_seal(output, message, message.size.toLong(), publicKey)

    override fun boxSealOpen(output: ByteArray, sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): Int =
        library.crypto_box_seal_open(output, sealed, sealed.size.toLong(), publicKey, privateKey)

    override fun genericHash(output: ByteArray, input: ByteArray): Int =
        library.crypto_generichash(output, output.size, input, input.size.toLong(), null, 0)

    override fun versionString(): String = version
}
