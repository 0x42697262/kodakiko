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
import kotlin.test.assertTrue

class CryptoReviewTest {
    private class OpenSpy(private val real: Sodium) : Sodium by real {
        val opened = mutableListOf<ByteArray>()

        override fun aeadDecrypt(ciphertext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray =
            real.aeadDecrypt(ciphertext, additionalData, nonce, key).also { opened += it }

        override fun boxSealOpen(sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray =
            real.boxSealOpen(sealed, publicKey, privateKey).also { opened += it }
    }

    @Test
    fun bomPrefixedShareIdIsRefused() {
        val keypair = sodium.boxKeypair()
        val idBytes = concatBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), utf8Encode(BINDING_ID))
        val payload = concatBytes(byteArrayOf(1, idBytes.size.toByte()), idBytes, counterBytes(13, KEY_LENGTH))
        val sealed = sodium.boxSeal(payload, keypair.publicKey)
        assertNotNull(catching<DecryptionError> { openSharePayload(sodium, sealed, keypair.publicKey, keypair.privateKey, BINDING_ID) })
    }

    @Test
    fun mutatingTheExposedEncryptorHeaderDoesNotChangeLaterChunks() {
        val plaintext = counterBytes(2, CHUNK + 5)
        val reference = createStreamEncryptor(seeded(1), key, plaintext.size.toLong())
        val mutated = createStreamEncryptor(seeded(1), key, plaintext.size.toLong())
        assertContentEquals(reference.header, mutated.header)
        mutated.header.fill(0)
        val expectedFirst = reference.encryptNext(plaintext.copyOfRange(0, CHUNK))
        val expectedLast = reference.encryptNext(plaintext.copyOfRange(CHUNK, plaintext.size))
        assertContentEquals(expectedFirst, mutated.encryptNext(plaintext.copyOfRange(0, CHUNK)))
        assertContentEquals(expectedLast, mutated.encryptNext(plaintext.copyOfRange(CHUNK, plaintext.size)))
    }

    @Test
    fun mutatingTheHeaderPassedToTheDecryptorDoesNotChangeLaterChunks() {
        val plaintext = counterBytes(2, CHUNK + 5)
        val encrypted = encryptWholeObject(sodium, plaintext)
        val passed = encrypted.header.copyOf()
        val decryptor = createStreamDecryptor(sodium, key, passed, plaintext.size.toLong())
        passed.fill(0)
        val decrypted = encrypted.chunks.map { decryptor.decryptNext(it) }
        decryptor.finish()
        assertTrue(bytesEqual(concatBytes(*decrypted.toTypedArray()), plaintext))
    }

    @Test
    fun streamChunkFunctionsRejectA31ByteKey() {
        val header = createStreamHeader(sodium)
        assertNotNull(catching<InvalidKeyLengthError> { encryptChunk(sodium, ByteArray(31), header, 0, true, ByteArray(3)) })
        assertNotNull(catching<InvalidKeyLengthError> { decryptChunk(sodium, ByteArray(31), header, 0, true, ByteArray(19)) })
    }

    @Test
    fun streamLayoutRejectsAChunkSizeExponentOutsideTheAllowedRange() {
        assertNotNull(catching<MalformedObjectError> { streamLayout(10, 99) })
        assertNotNull(catching<MalformedObjectError> { streamLayout(10, 9) })
        assertNotNull(catching<MalformedObjectError> { streamLayout(10, 25) })
        assertNotNull(catching<MalformedObjectError> { layoutFromCiphertextSize(40, 99) })
    }

    @Test
    fun unwrapKeyWipesAWrongLengthOpenedKeyBeforeThrowing() {
        val spy = OpenSpy(sodium)
        val shortBlob = sealBlob(sodium, key, counterBytes(1, 31), AdContext.MEDIA_KEY_BY_ENTRY, BINDING_ID)
        assertNotNull(catching<MalformedPayloadError> { unwrapKey(spy, key, shortBlob, AdContext.MEDIA_KEY_BY_ENTRY, BINDING_ID) })
        assertEquals(1, spy.opened.size)
        assertTrue(spy.opened[0].all { it == 0.toByte() })
    }

    @Test
    fun openSharePayloadWipesTheOpenedPayloadAndReturnsASeparateKeyCopy() {
        val keypair = sodium.boxKeypair()
        val collectionKey = counterBytes(13, KEY_LENGTH)
        val sealed = sodium.boxSeal(buildSharePayload(BINDING_ID, collectionKey), keypair.publicKey)
        val spy = OpenSpy(sodium)
        val result = openSharePayload(spy, sealed, keypair.publicKey, keypair.privateKey, BINDING_ID)
        assertTrue(bytesEqual(result, collectionKey))
        assertTrue(spy.opened[0].all { it == 0.toByte() })
    }

    @Test
    fun openSharePayloadWipesTheOpenedPayloadWhenTheIdDoesNotMatch() {
        val keypair = sodium.boxKeypair()
        val sealed = sodium.boxSeal(buildSharePayload(BINDING_ID, counterBytes(13, KEY_LENGTH)), keypair.publicKey)
        val spy = OpenSpy(sodium)
        assertNotNull(catching<DecryptionError> { openSharePayload(spy, sealed, keypair.publicKey, keypair.privateKey, OTHER_BINDING_ID) })
        assertTrue(spy.opened[0].all { it == 0.toByte() })
    }

    @Test
    fun withRandomSourceReplacesOnlyRandomBytes() {
        val seededSodium = sodium.withRandomSource(createCounterRandom(5))
        assertContentEquals(counterBytes(5, 3), seededSodium.randomBytes(3))
        assertContentEquals(counterBytes(8, 3), seededSodium.randomBytes(3))
        assertContentEquals(sodium.genericHash(32, byteArrayOf(1)), seededSodium.genericHash(32, byteArrayOf(1)))
    }

    @Test
    fun passwordToBytesAppliesNfkcNotNfc() {
        assertContentEquals(passwordToBytes("\u00e9"), passwordToBytes("e\u0301"))
        assertTrue(!passwordToBytes("e\u0301").contentEquals(passwordToBytes("e")))
        assertContentEquals(passwordToBytes("fi"), passwordToBytes("\uFB01"))
    }
}
