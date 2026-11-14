package dev.kodakiko.crypto

import dev.kodakiko.crypto.TestSupport.BINDING_ID
import dev.kodakiko.crypto.TestSupport.CHUNK
import dev.kodakiko.crypto.TestSupport.KEY_LENGTH
import dev.kodakiko.crypto.TestSupport.OTHER_BINDING_ID
import dev.kodakiko.crypto.TestSupport.catching
import dev.kodakiko.crypto.TestSupport.encryptWholeObject
import dev.kodakiko.crypto.TestSupport.key
import dev.kodakiko.crypto.TestSupport.seeded
import dev.kodakiko.crypto.TestSupport.sodium
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoErrorTest {
    private fun streamNonceOf(header: ByteArray): ByteArray = streamNonce(parseStreamHeader(header), 0, true)

    @Test
    fun sealedBlobFailures() {
        val context = AdContext.ENTRY_METADATA
        val blob = sealBlob(sodium, key, counterBytes(1, 50), context, BINDING_ID)
        val flipped = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNotNull(catching<DecryptionError> { openBlob(sodium, key, flipped, context, BINDING_ID) })
        assertNotNull(catching<DecryptionError> { openBlob(sodium, key, blob, context, OTHER_BINDING_ID) })
        val wrongVersion = blob.copyOf().also { it[0] = 2 }
        assertNotNull(catching<UnsupportedFormatError> { openBlob(sodium, key, wrongVersion, context, BINDING_ID) })
        assertNotNull(catching<MalformedPayloadError> { openBlob(sodium, key, ByteArray(40), context, BINDING_ID) })
    }

    @Test
    fun paddingKeyWrappingAndKeyLengthFailures() {
        assertNotNull(catching<MalformedPayloadError> { unpadIso7816(byteArrayOf(1, 2), 2) })
        assertNotNull(catching<InvalidKeyLengthError> { wrapKey(sodium, key, ByteArray(31), AdContext.MEDIA_KEY_BY_ENTRY, BINDING_ID) })
        val shortBlob = sealBlob(sodium, key, ByteArray(31), AdContext.MEDIA_KEY_BY_ENTRY, BINDING_ID)
        assertNotNull(catching<MalformedPayloadError> { unwrapKey(sodium, key, shortBlob, AdContext.MEDIA_KEY_BY_ENTRY, BINDING_ID) })
    }

    @Test
    fun paddingRoundTripAddsAtLeastOneByte() {
        assertEquals(2 * PADDING_BLOCK_BYTES, padIso7816(ByteArray(PADDING_BLOCK_BYTES), PADDING_BLOCK_BYTES).size)
        assertContentEquals(counterBytes(1, 10), unpadIso7816(padIso7816(counterBytes(1, 10), PADDING_BLOCK_BYTES), PADDING_BLOCK_BYTES))
    }

    @Test
    fun streamLayoutAndHeaderFailures() {
        assertNotNull(catching<MalformedObjectError> { layoutFromCiphertextSize(39, 20) })
        assertEquals(
            StreamLayout(
                plaintextSize = 0,
                chunkSizeExponent = 20,
                chunkSize = 1_048_576,
                chunkCount = 1,
                chunkCiphertextSize = 1_048_592,
                ciphertextSize = 40,
            ),
            layoutFromCiphertextSize(40, 20),
        )
        val header = encryptWholeObject(sodium, ByteArray(0)).header
        assertEquals(STREAM_HEADER_BYTES, header.size)
        assertContentEquals(STREAM_MAGIC, header.copyOfRange(0, STREAM_MAGIC.size))
        assertNotNull(catching<MalformedObjectError> { parseStreamHeader(header.copyOf().also { it[0] = 0 }) })
        assertNotNull(catching<UnsupportedFormatError> { parseStreamHeader(header.copyOf().also { it[4] = 2 }) })
        assertNotNull(catching<MalformedObjectError> { parseStreamHeader(header.copyOf().also { it[7] = 1 }) })
        assertNotNull(catching<MalformedObjectError> { parseStreamHeader(header.copyOf().also { it[5] = 30 }) })
    }

    @Test
    fun streamAdditionalDataIsUtf8OfLowercaseHexOfTheHeader() {
        val header = createStreamHeader(seeded(0))
        val additionalData = streamAdditionalData(header)
        val expectedHex = "4b444b5301140000000102030405060708090a0b0c0d0e0f"
        assertEquals(2 * STREAM_HEADER_BYTES, expectedHex.length)
        assertContentEquals(utf8Encode(expectedHex), additionalData)
        assertEquals(expectedHex, utf8Decode(additionalData))
    }

    @Test
    fun streamChunksBindTheHexAdditionalDataNotTheRawHeader() {
        val seen = mutableListOf<ByteArray>()
        val spy = object : Sodium by sodium {
            override fun aeadEncrypt(plaintext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
                seen += additionalData
                return sodium.aeadEncrypt(plaintext, additionalData, nonce, key)
            }
        }
        val encrypted = encryptWholeObject(spy, counterBytes(3, 5))
        assertEquals(1, seen.size)
        assertContentEquals(streamAdditionalData(encrypted.header), seen[0])
        assertNotNull(catching<DecryptionError> { sodium.aeadDecrypt(encrypted.chunks[0], encrypted.header, streamNonceOf(encrypted.header), key) })
        assertContentEquals(counterBytes(3, 5), decryptChunk(sodium, key, encrypted.header, 0, true, encrypted.chunks[0]))
    }

    @Test
    fun streamEncryptorFailures() {
        val short = createStreamEncryptor(sodium, key, CHUNK + 5L)
        assertNotNull(catching<InvalidChunkError> { short.encryptNext(ByteArray(CHUNK - 1)) })
        val complete = createStreamEncryptor(sodium, key, 3)
        complete.encryptNext(ByteArray(3))
        assertTrue(complete.isDone())
        assertNotNull(catching<InvalidChunkError> { complete.encryptNext(ByteArray(3)) })
    }

    @Test
    fun streamDecryptorTruncationAndLengthFailures() {
        val plaintext = counterBytes(2, CHUNK + 5)
        val encrypted = encryptWholeObject(sodium, plaintext)
        val truncated = createStreamDecryptor(sodium, key, encrypted.header, plaintext.size.toLong())
        truncated.decryptNext(encrypted.chunks[0])
        assertNotNull(catching<TruncatedObjectError> { truncated.finish() })
        val outOfOrder = createStreamDecryptor(sodium, key, encrypted.header, plaintext.size.toLong())
        assertEquals(21, encrypted.chunks[1].size)
        assertNotNull(catching<MalformedObjectError> { outOfOrder.decryptNext(encrypted.chunks[1]) })
    }

    @Test
    fun streamTruncationAttackAtAChunkBoundary() {
        val encrypted = encryptWholeObject(sodium, counterBytes(4, 3 * CHUNK))
        val decryptor = createStreamDecryptor(sodium, key, encrypted.header, 2L * CHUNK)
        decryptor.decryptNext(encrypted.chunks[0])
        assertNotNull(catching<DecryptionError> { decryptor.decryptNext(encrypted.chunks[1]) })
    }

    @Test
    fun writeUint56BigEndianRejectsTwoToTheFiftyThird() {
        assertNotNull(catching<InvalidChunkError> { writeUint56BigEndian(ByteArray(8), 0, 1L shl 53) })
        val target = ByteArray(7)
        writeUint56BigEndian(target, 0, 0x0102030405L)
        assertEquals("00000102030405", bytesToHex(target))
    }

    @Test
    fun recoveryWordFailures() {
        val words = encodeRecoveryKey(sodium, counterBytes(12, KEY_LENGTH))
        val countError = catching<InvalidRecoveryWordsError> { decodeRecoveryWords(sodium, words.take(23)) }
        assertNotNull(countError)
        assertNull(countError.wordPosition)
        assertEquals(CryptoText.INVALID_RECOVERY_WORD_COUNT, countError.message)

        val unknown = words.toMutableList().also { it[4] = "notaword" }
        val unknownError = catching<InvalidRecoveryWordsError> { decodeRecoveryWords(sodium, unknown) }
        assertNotNull(unknownError)
        assertEquals(4, unknownError.wordPosition)
        assertEquals(CryptoText.unknownRecoveryWord(5), unknownError.message)

        val checksum = words.toMutableList().also { it[23] = WORDLIST_EN[WORDLIST_EN.indexOf(words[23]) xor 1] }
        val checksumError = catching<InvalidRecoveryWordsError> { decodeRecoveryWords(sodium, checksum) }
        assertNotNull(checksumError)
        assertNull(checksumError.wordPosition)
        assertEquals(CryptoText.RECOVERY_CHECKSUM_MISMATCH, checksumError.message)
    }

    @Test
    fun openSharePayloadWithADifferentExpectedId() {
        val keypair = sodium.boxKeypair()
        val sealed = sodium.boxSeal(buildSharePayload(BINDING_ID, counterBytes(13, KEY_LENGTH)), keypair.publicKey)
        assertNotNull(catching<DecryptionError> { openSharePayload(sodium, sealed, keypair.publicKey, keypair.privateKey, OTHER_BINDING_ID) })
    }

    @Test
    fun cryptoTextMatchesTheTypeScriptMessages() {
        assertEquals("The data could not be decrypted.", CryptoText.DECRYPTION_FAILED)
        assertEquals("The recovery key must have exactly 24 words.", CryptoText.INVALID_RECOVERY_WORD_COUNT)
        assertEquals("Word 5 is not in the word list.", CryptoText.unknownRecoveryWord(5))
        assertEquals("The recovery words do not match. Check them and try again.", CryptoText.RECOVERY_CHECKSUM_MISMATCH)
    }
}
