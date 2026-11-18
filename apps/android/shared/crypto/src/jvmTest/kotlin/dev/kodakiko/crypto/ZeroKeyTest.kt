package dev.kodakiko.crypto

import dev.kodakiko.crypto.TestSupport.BINDING_ID
import dev.kodakiko.crypto.TestSupport.catching
import dev.kodakiko.crypto.TestSupport.key
import dev.kodakiko.crypto.TestSupport.sodium
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZeroKeyTest {
    private val context = AdContext.ENTRY_KEY_BY_COLLECTION

    private fun zeroKey(): ByteArray = ByteArray(KEY_BYTES)

    private fun blobUnderZeroKey(plaintext: ByteArray): ByteArray {
        val nonce = sodium.randomBytes(AEAD_NONCE_BYTES)
        val ciphertext = sodium.aeadEncrypt(plaintext, buildAdditionalData(context, BINDING_ID), nonce, zeroKey())
        return concatBytes(byteArrayOf(FORMAT_VERSION_SEALED_BLOB.toByte()), nonce, ciphertext)
    }

    private fun requireZeroKeyError(action: () -> Unit) {
        val error = assertNotNull(catching<ZeroKeyError>(action), "an all-zero key must be refused")
        assertTrue(error is CryptoException)
    }

    @Test
    fun sealBlobRefusesAnAllZeroKey() {
        requireZeroKeyError { sealBlob(sodium, zeroKey(), counterBytes(1, 40), context, BINDING_ID) }
    }

    @Test
    fun openBlobRefusesAnAllZeroKeyEvenForABlobThatWouldOpen() {
        val blob = blobUnderZeroKey(counterBytes(1, 40))
        requireZeroKeyError { openBlob(sodium, zeroKey(), blob, context, BINDING_ID) }
    }

    @Test
    fun wrapKeyRefusesAnAllZeroWrappingKey() {
        requireZeroKeyError { wrapKey(sodium, zeroKey(), counterBytes(7, KEY_BYTES), context, BINDING_ID) }
    }

    @Test
    fun wrapKeyRefusesAnAllZeroKeyToWrap() {
        requireZeroKeyError { wrapKey(sodium, key, zeroKey(), context, BINDING_ID) }
    }

    @Test
    fun unwrapKeyRefusesAnAllZeroWrappingKey() {
        val wrapped = blobUnderZeroKey(counterBytes(7, KEY_BYTES))
        requireZeroKeyError { unwrapKey(sodium, zeroKey(), wrapped, context, BINDING_ID) }
    }

    @Test
    fun aStreamEncryptorRefusesAnAllZeroVariantKey() {
        requireZeroKeyError { createStreamEncryptor(sodium, zeroKey(), 10) }
    }

    @Test
    fun aStreamEncryptorRefusesAVariantKeyWipedAfterItWasCreated() {
        val variantKey = counterBytes(3, KEY_BYTES)
        val encryptor = createStreamEncryptor(sodium, variantKey, 10)
        wipe(variantKey)
        requireZeroKeyError { encryptor.encryptNext(counterBytes(1, 10)) }
    }

    @Test
    fun aStreamDecryptorRefusesAnAllZeroVariantKey() {
        val header = createStreamHeader(sodium)
        requireZeroKeyError { createStreamDecryptor(sodium, zeroKey(), header, 10) }
    }

    @Test
    fun singleChunksRefuseAnAllZeroVariantKey() {
        val header = createStreamHeader(sodium)
        val ciphertext = sodium.aeadEncrypt(counterBytes(1, 10), streamAdditionalData(header), streamNonce(parseStreamHeader(header), 0, true), zeroKey())
        requireZeroKeyError { encryptChunk(sodium, zeroKey(), header, 0, true, counterBytes(1, 10)) }
        requireZeroKeyError { decryptChunk(sodium, zeroKey(), header, 0, true, ciphertext) }
    }

    @Test
    fun aSharePayloadRefusesAnAllZeroCollectionKey() {
        requireZeroKeyError { buildSharePayload(BINDING_ID, zeroKey()) }
    }
}
