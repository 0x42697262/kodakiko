package dev.kodakiko.exporting

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.concatBytes
import dev.kodakiko.crypto.counterBytes
import dev.kodakiko.crypto.createCounterRandom
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.withRandomSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KdkxRoundTripTest {
    private val opsLimit = 2L
    private val memLimitBytes = 8_388_608L
    private val plaintext = counterBytes(7, KDKX_CHUNK_BYTES * 2 + KDKX_CHUNK_BYTES / 2)

    private fun write(pieces: List<Int>): Pair<ByteArray, ByteArray> {
        val sodium: Sodium = createSodium().withRandomSource(createCounterRandom(3))
        val params = KdkxParams(sodium.randomBytes(KDKX_SALT_BYTES), opsLimit, memLimitBytes)
        val key = deriveKdkxKey(sodium, "pass", params)
        val writer = createKdkxWriter(sodium, key, params)
        val chunks = mutableListOf(writer.header)
        var cursor = 0
        for (size in pieces) {
            chunks.addAll(writer.push(plaintext.copyOfRange(cursor, cursor + size)))
            cursor += size
        }
        chunks.add(writer.finish())
        return key to concatBytes(*chunks.toTypedArray())
    }

    @Test
    fun manySmallPushesWriteTheSameFileAsOnePush() {
        val small = generateSequence { 65_537 }.take(plaintext.size / 65_537).toList()
        val pieces = small + (plaintext.size - small.sum())
        val (_, once) = write(listOf(plaintext.size))
        val (_, many) = write(pieces)
        assertContentEquals(once, many)
        assertEquals(KdkxConstants.PREFIX_BYTES + 24 + plaintext.size + 16 * 3, once.size)
    }

    @Test
    fun decryptsWhatItWrote() {
        val (key, file) = write(listOf(plaintext.size))
        assertContentEquals(plaintext, decryptKdkx(createSodium(), key, file))
        assertEquals(opsLimit, readKdkxParams(file).opsLimit)
        assertEquals(memLimitBytes, readKdkxParams(file).memLimitBytes)
    }
}
