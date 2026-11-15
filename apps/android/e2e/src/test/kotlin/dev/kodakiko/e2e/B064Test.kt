package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.STREAM_MAGIC
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.media.decryptWhole
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B064Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }

    @Test
    fun whenTheBucketObjectsAreReadDirectlyThenEachStartsWithKdksAndCannotBeReadWithoutKeys() = e2e {
        val a = signUpDevice("A")
        a.syncUntilIdle()
        val photo = randomMedia(E2eConstants.SMALL_MEDIA_BYTES)
        val entry = a.createSyncedEntry(EntryInput(listOf(photo), "secret note", listOf("private")))
        val mediaId = entry.mediaIdAt(0)
        val plaintexts = mapOf(
            ObjectVariant.ORIGINAL to photo,
            ObjectVariant.THUMBNAIL to decodeBytes(E2eConstants.THUMBNAIL_BASE64URL),
        )

        for ((variant, plaintext) in plaintexts) {
            val (objectRow, ciphertext) = a.downloadCiphertext(mediaId, variant)
            assertArrayEquals(STREAM_MAGIC, ciphertext.copyOf(E2eConstants.STREAM_MAGIC_BYTES))
            assertFalse(contains(ciphertext, plaintext.copyOfRange(E2eConstants.STREAM_MAGIC_BYTES, plaintext.size)))
            val wrongKey = a.sodium.randomBytes(KEY_BYTES)
            val failure = runCatching { decryptWhole(a.sodium, wrongKey, ciphertext, objectRow.plaintextSize) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is DecryptionError)
            assertArrayEquals(plaintext, a.downloadAndDecrypt(entry.entryId, mediaId, variant))
        }
        assertEquals(1, entry.mediaIds.size)
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
