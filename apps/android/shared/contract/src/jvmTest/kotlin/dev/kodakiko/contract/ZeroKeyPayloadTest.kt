package dev.kodakiko.contract

import dev.kodakiko.crypto.AEAD_NONCE_BYTES
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.FORMAT_VERSION_SEALED_BLOB
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.PADDING_BLOCK_BYTES
import dev.kodakiko.crypto.ZeroKeyError
import dev.kodakiko.crypto.buildAdditionalData
import dev.kodakiko.crypto.concatBytes
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.padIso7816
import dev.kodakiko.crypto.utf8Encode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ZeroKeyPayloadTest {
    private val sodium = createSodium()
    private val bindingId = "AAAAAAAAAAAAAAAAAAAAAA"
    private val context = AdContext.COLLECTION_METADATA
    private val value = CollectionMetadataV1("Trip", "2026-06-01T10:00:00.000Z")

    private fun failureOf(action: () -> Unit): Throwable? =
        try {
            action()
            null
        } catch (error: Throwable) {
            error
        }

    private fun requireZeroKeyError(action: () -> Unit) {
        val failure = failureOf(action) ?: fail("an all-zero key must be refused")
        assertTrue(failure is ZeroKeyError, "expected ZeroKeyError but got $failure")
    }

    @Test
    fun sealPayloadRefusesAnAllZeroKey() {
        requireZeroKeyError { sealPayload(sodium, ByteArray(KEY_BYTES), value, ContractSchemas.collectionMetadataV1, context, bindingId) }
    }

    @Test
    fun openPayloadRefusesAnAllZeroKeyEvenForAPayloadThatWouldOpen() {
        val padded = padIso7816(utf8Encode(ContractJson.stringify(ContractSchemas.collectionMetadataV1.encode(value))), PADDING_BLOCK_BYTES)
        val nonce = sodium.randomBytes(AEAD_NONCE_BYTES)
        val ciphertext = sodium.aeadEncrypt(padded, buildAdditionalData(context, bindingId), nonce, ByteArray(KEY_BYTES))
        val blob = concatBytes(byteArrayOf(FORMAT_VERSION_SEALED_BLOB.toByte()), nonce, ciphertext)
        requireZeroKeyError {
            openPayload(
                sodium,
                ByteArray(KEY_BYTES),
                blob,
                ContractSchemas.collectionMetadataV1,
                COLLECTION_METADATA_SCHEMA_VERSION,
                emptyMap(),
                context,
                bindingId,
            )
        }
    }
}
