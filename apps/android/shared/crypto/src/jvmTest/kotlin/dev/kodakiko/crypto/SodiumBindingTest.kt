package dev.kodakiko.crypto

import dev.kodakiko.crypto.TestSupport.catching
import dev.kodakiko.crypto.TestSupport.sodium
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SodiumBindingTest {
    @Test
    fun createSodiumReturnsOneSharedInstance() {
        assertSame(createSodium(), createSodium())
    }

    @Test
    fun wrongLengthInputsAreRejectedBeforeReachingLibsodium() {
        val nonce = ByteArray(AEAD_NONCE_BYTES)
        assertNotNull(catching<InvalidKeyLengthError> { sodium.aeadEncrypt(ByteArray(1), ByteArray(0), nonce, ByteArray(31)) })
        assertNotNull(catching<InvalidKeyLengthError> { sodium.aeadEncrypt(ByteArray(1), ByteArray(0), ByteArray(23), ByteArray(32)) })
        assertNotNull(catching<DecryptionError> { sodium.aeadDecrypt(ByteArray(17), ByteArray(0), nonce, ByteArray(31)) })
        assertNotNull(catching<DecryptionError> { sodium.aeadDecrypt(ByteArray(15), ByteArray(0), nonce, ByteArray(32)) })
        assertNotNull(catching<InvalidKeyLengthError> { sodium.boxSeal(ByteArray(1), ByteArray(31)) })
        assertNotNull(catching<DecryptionError> { sodium.boxSealOpen(ByteArray(47), ByteArray(32), ByteArray(32)) })
        assertNotNull(catching<InvalidKeyLengthError> { sodium.boxSeedKeypair(ByteArray(31)) })
        assertNotNull(catching<InvalidKeyLengthError> { sodium.kdfDerive(32, 1, KdfContext.LOGIN.value, ByteArray(31)) })
        assertNotNull(catching<InvalidKeyLengthError> { sodium.genericHash(8, ByteArray(1)) })
        val input = Argon2idInput(ByteArray(1), ByteArray(15), 2, 8_388_608, 32)
        assertNotNull(catching<InvalidKeyLengthError> { sodium.argon2id(input) })
    }

    @Test
    fun aeadDecryptWithAnotherNonceIsADecryptionError() {
        val key = counterBytes(1, 32)
        val sealed = sodium.aeadEncrypt(byteArrayOf(1, 2), byteArrayOf(3), ByteArray(AEAD_NONCE_BYTES), key)
        assertNotNull(catching<DecryptionError> { sodium.aeadDecrypt(sealed, byteArrayOf(3), ByteArray(AEAD_NONCE_BYTES) { 1 }, key) })
    }
}
