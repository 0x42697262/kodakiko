package dev.kodakiko.service.entries

import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.streamLayout
import org.junit.Assert.assertTrue
import org.junit.Test

class MaxPlaintextBytesTest {
    private val ciphertextLimits = listOf(0L, 24L, 40L, 1000L, 1_048_600L, 1_048_616L, 5_000_000L, 3_758_096_384L)

    @Test
    fun keepsTheCiphertextWithinEachLimitAndIsTheLargestSizeThatDoes() {
        for (limit in ciphertextLimits) {
            val plaintext = maxPlaintextBytes(limit)
            if (limit >= 40) {
                assertTrue("limit $limit", streamLayout(plaintext, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize <= limit)
            }
            assertTrue("limit $limit", streamLayout(plaintext + 1, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize > limit)
        }
    }
}
