package dev.kodakiko.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceVectorsTest {
    private fun resource(path: String): String =
        DeviceVectorsTest::class.java.classLoader!!.getResourceAsStream(path)!!.bufferedReader().readText()

    @Test
    fun allThirtyEightVectorsPassOnTheDevice() {
        val sodium = createSodium()
        val results = checkVectors(sodium) { name -> resource("vectors/$name") }
        println("KOTLIN-CRYPTO device libsodium=${sodium.libraryVersion()} passed=${results.count { it.passed }}/${results.size}")
        assertEquals(38, results.size)
        assertTrue(results.filterNot { it.passed }.joinToString { "${it.file} ${it.name}" }, results.all { it.passed })
    }

    @Test
    fun accountArgon2idFitsTheBudgetOnTheDevice() {
        val sodium = createSodium()
        val started = System.nanoTime()
        val output = sodium.argon2id(
            Argon2idInput(
                password = passwordToBytes("correct horse battery staple"),
                salt = ByteArray(16) { it.toByte() },
                opsLimit = 16,
                memLimitBytes = 256L * 1024 * 1024,
                outputLength = 32,
            ),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("KOTLIN-CRYPTO device argon2id-256MiBx16 ms=$elapsedMs")
        assertEquals(32, output.size)
        assertTrue("Argon2id took $elapsedMs ms", elapsedMs < 8_000)
    }
}
