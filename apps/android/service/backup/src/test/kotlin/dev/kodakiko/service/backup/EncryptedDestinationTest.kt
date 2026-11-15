package dev.kodakiko.service.backup

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.exporting.KDKX_SALT_BYTES
import dev.kodakiko.exporting.decryptKdkx
import dev.kodakiko.exporting.deriveKdkxKey
import dev.kodakiko.exporting.isKdkx
import dev.kodakiko.exporting.readKdkxParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedDestinationTest {
    private val sodium: Sodium = createSodium()

    private fun encrypted(inner: MemoryDestination): ExportDestination =
        encryptedDestination(sodium, inner, "export passphrase", opsLimit = 2, memLimitBytes = 8_388_608)

    @Test
    fun writesEachPartAsNameKdkxThatDecryptsWithThePassphraseToTheOriginalBytes() {
        val inner = MemoryDestination(1_000)
        val destination = encrypted(inner)
        assertEquals(1_000L, destination.partMaxBytes)

        val part = destination.createPart("kodakiko-export-01.zip")
        part.write(byteArrayOf(1, 2, 3))
        part.write(byteArrayOf(4, 5))
        part.close()

        val file = inner.parts.single { it.name == "kodakiko-export-01.zip.kdkx" }.bytes()
        val key = deriveKdkxKey(sodium, "export passphrase", readKdkxParams(file))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decryptKdkx(sodium, key, file))
    }

    @Test
    fun passesAnAbortThroughToTheInnerPart() {
        val inner = MemoryDestination(1_000)
        val part = encrypted(inner).createPart("x.zip")

        part.abort()

        assertEquals(listOf("x.zip.kdkx"), inner.parts.filter { it.aborted }.map { it.name })
        assertFalse(inner.parts.single().closed)
    }

    @Test
    fun theHeaderIsWrittenWhenThePartIsCreatedWithTheCostAndARandomSalt() {
        val inner = MemoryDestination(1_000)
        val destination = encrypted(inner)

        destination.createPart("a.zip")
        destination.createPart("b.zip")

        val first = inner.parts[0].bytes()
        assertTrue(isKdkx(first))
        val other = MemoryDestination(1_000)
        encrypted(other).createPart("a.zip")
        val second = other.parts[0].bytes()
        assertTrue(isKdkx(second))
        assertEquals(first.size, second.size)
        assertEquals(first.size, inner.parts[1].bytes().size)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun aClosedPartRecordsTheCostAndSaltInItsHeader() {
        val inner = MemoryDestination(1_000)
        val destination = encrypted(inner)
        listOf("a.zip", "b.zip").forEach { name ->
            destination.createPart(name).apply {
                write(byteArrayOf(9))
                close()
            }
        }

        val params = inner.parts.map { readKdkxParams(it.bytes()) }
        assertTrue(params.all { it.opsLimit == 2L && it.memLimitBytes == 8_388_608L && it.salt.size == KDKX_SALT_BYTES })
        assertArrayEquals(params[0].salt, params[1].salt)
        assertTrue(inner.parts.all { it.closed })
    }

    @Test
    fun aPartLargerThanOneKdkxChunkDecryptsWhole() {
        val inner = MemoryDestination(1_000)
        val part = encrypted(inner).createPart("big.zip")
        val payload = patternBytes(2_621_440, 7)

        var offset = 0
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + 300_000)
            part.write(payload.copyOfRange(offset, end))
            offset = end
        }
        part.close()

        assertArrayEquals(payload, decryptKdkxFile(sodium, inner.parts.single().bytes(), "export passphrase"))
    }

    @Test
    fun anEmptyPartStillDecryptsToNothing() {
        val inner = MemoryDestination(1_000)
        encrypted(inner).createPart("empty.zip").close()
        assertArrayEquals(ByteArray(0), decryptKdkxFile(sodium, inner.parts.single().bytes(), "export passphrase"))
    }
}
