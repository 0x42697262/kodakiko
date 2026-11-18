package dev.kodakiko.crypto

import dev.kodakiko.crypto.TestSupport.BINDING_ID
import dev.kodakiko.crypto.TestSupport.CHUNK
import dev.kodakiko.crypto.TestSupport.KEY_LENGTH
import dev.kodakiko.crypto.TestSupport.encryptWholeObject
import dev.kodakiko.crypto.TestSupport.key
import dev.kodakiko.crypto.TestSupport.sodium
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CryptoRoundTripTest {
    @Test
    fun sealBlobOpenBlobForEveryContextAndSeveralLengths() {
        for (context in AdContext.entries) {
            for (length in listOf(0, 100, 100_000)) {
                val plaintext = counterBytes(3, length)
                val blob = sealBlob(sodium, key, plaintext, context, BINDING_ID)
                assertTrue(bytesEqual(openBlob(sodium, key, blob, context, BINDING_ID), plaintext))
            }
        }
    }

    @Test
    fun thereAreThirteenAdContextsWithTheTypeScriptStrings() {
        assertEquals(13, AdContext.entries.size)
        assertEquals("kodakiko/v1/wrap/master-key-by-kek", AdContext.MASTER_KEY_BY_KEK.value)
        assertEquals("kodakiko/v1/wrap/media-key-by-entry", AdContext.MEDIA_KEY_BY_ENTRY.value)
        assertEquals("kodakiko/v1/entry-metadata", AdContext.ENTRY_METADATA.value)
        assertEquals("kodakiko/v1/wrap/link-secret-by-master", AdContext.LINK_SECRET_BY_MASTER.value)
    }

    @Test
    fun wrapKeyUnwrapKey() {
        val inner = counterBytes(5, KEY_LENGTH)
        val wrapped = wrapKey(sodium, key, inner, AdContext.COLLECTION_KEY_BY_MASTER, BINDING_ID)
        assertEquals(73, wrapped.size)
        assertTrue(bytesEqual(unwrapKey(sodium, key, wrapped, AdContext.COLLECTION_KEY_BY_MASTER, BINDING_ID), inner))
    }

    @Test
    fun streamEncryptorToDecryptorWithTwoAndAHalfChunks() {
        val plaintext = counterBytes(7, CHUNK * 5 / 2)
        val encrypted = encryptWholeObject(sodium, plaintext)
        assertTrue(encrypted.encryptor.isDone())
        assertEquals(3L, encrypted.layout.chunkCount)
        val decryptor = createStreamDecryptor(sodium, key, encrypted.header, plaintext.size.toLong())
        val decrypted = encrypted.chunks.map { decryptor.decryptNext(it) }
        decryptor.finish()
        assertTrue(bytesEqual(concatBytes(*decrypted.toTypedArray()), plaintext))
    }

    @Test
    fun streamRandomAccessByChunkIndex() {
        val plaintext = counterBytes(7, CHUNK * 5 / 2)
        val encrypted = encryptWholeObject(sodium, plaintext)
        val last = plaintext.size - 1
        for ((start, end) in listOf(0 to 0, last to last, (CHUNK - 10) to (CHUNK + 10))) {
            val range = chunkRangeForPlaintextRange(encrypted.layout, start.toLong(), end.toLong())
            val parts = (range.firstChunk..range.lastChunk).map { index ->
                decryptChunk(sodium, key, encrypted.header, index, index == encrypted.layout.chunkCount - 1, encrypted.chunks[index.toInt()])
            }
            val joined = concatBytes(*parts.toTypedArray())
            val offset = (start - range.firstChunk * CHUNK).toInt()
            assertContentEquals(plaintext.copyOfRange(start, end + 1), joined.copyOfRange(offset, offset + (end - start + 1)))
        }
    }

    @Test
    fun recoveryWordsEncodeDecodeAndNormalizeInput() {
        val recoveryKey = counterBytes(11, KEY_LENGTH)
        val words = encodeRecoveryKey(sodium, recoveryKey)
        assertEquals(24, words.size)
        assertTrue(bytesEqual(decodeRecoveryWords(sodium, words), recoveryKey))
        assertEquals(listOf("abandon", "ability"), normalizeRecoveryInput("  Abandon\tABILITY  "))
        assertEquals(2048, WORDLIST_EN.size)
        assertEquals("abandon", WORDLIST_EN.first())
        assertEquals("zoo", WORDLIST_EN.last())
    }

    @Test
    fun sharePayloadBuildSealOpenAndFingerprintFormat() {
        val keypair = sodium.boxKeypair()
        val collectionKey = counterBytes(13, KEY_LENGTH)
        val sealed = sodium.boxSeal(buildSharePayload(BINDING_ID, collectionKey), keypair.publicKey)
        val opened = openSharePayload(sodium, sealed, keypair.publicKey, keypair.privateKey, BINDING_ID)
        assertTrue(bytesEqual(opened, collectionKey))
        assertTrue(Regex("^[0-9a-f]{4}( [0-9a-f]{4}){3}$").matches(publicKeyFingerprint(sodium, keypair.publicKey)))
    }

    @Test
    fun hexRoundTripAndConstantTimeEquality() {
        val bytes = counterBytes(250, 10)
        assertEquals("fafbfcfdfeff00010203", bytesToHex(bytes))
        assertContentEquals(bytes, hexToBytes("fafbfcfdfeff00010203"))
        assertTrue(bytesEqual(bytes, bytes.copyOf()))
        assertTrue(!bytesEqual(bytes, bytes.copyOf(9)))
        val toWipe = counterBytes(1, 5)
        wipe(toWipe)
        assertTrue(toWipe.all { it == 0.toByte() })
    }

    @Test
    fun sodiumIsLibsodium1020() {
        assertEquals("1.0.20", sodium.libraryVersion())
    }

    @Test
    fun seededKeypairIsDeterministic() {
        val seed = counterBytes(9, 32)
        val first = sodium.boxSeedKeypair(seed)
        val second = sodium.boxSeedKeypair(seed)
        assertContentEquals(first.publicKey, second.publicKey)
        assertContentEquals(first.privateKey, second.privateKey)
        val sealed = sodium.boxSeal(byteArrayOf(1, 2, 3), first.publicKey)
        assertContentEquals(byteArrayOf(1, 2, 3), sodium.boxSealOpen(sealed, first.publicKey, first.privateKey))
    }
}
