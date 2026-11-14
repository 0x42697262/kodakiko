package dev.kodakiko.crypto

import java.io.File

object TestSupport {
    const val CHUNK = 1 shl 20
    const val KEY_LENGTH = 32
    const val BINDING_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    const val OTHER_BINDING_ID = "BBBBBBBBBBBBBBBBBBBBBB"

    val key: ByteArray = counterBytes(1, KEY_LENGTH)

    val sodium: Sodium by lazy { createSodium() }

    fun seeded(seed: Int): Sodium = sodium.withRandomSource(createCounterRandom(seed))

    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            ?: error("repository root not found")
    }

    fun resource(path: String): String =
        TestSupport::class.java.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("missing test resource $path")

    val goldenDirectory: File get() = File(repoRoot, "tests/fixtures/golden")

    val artifactDirectory: File get() = File(repoRoot, "apps/android/build/kotlin-artifacts")

    inline fun <reified T : Throwable> catching(action: () -> Unit): T? =
        try {
            action()
            null
        } catch (error: Throwable) {
            error as? T ?: throw AssertionError("expected ${T::class.simpleName} but got $error", error)
        }

    class EncryptedObject(val header: ByteArray, val chunks: List<ByteArray>, val layout: StreamLayout, val encryptor: StreamEncryptor)

    fun encryptWholeObject(sodium: Sodium, plaintext: ByteArray): EncryptedObject {
        val encryptor = createStreamEncryptor(sodium, key, plaintext.size.toLong())
        val chunks = (0 until encryptor.layout.chunkCount.toInt()).map { index ->
            val start = index * CHUNK
            encryptor.encryptNext(plaintext.copyOfRange(start, minOf(start + CHUNK, plaintext.size)))
        }
        return EncryptedObject(encryptor.header, chunks, encryptor.layout, encryptor)
    }
}
