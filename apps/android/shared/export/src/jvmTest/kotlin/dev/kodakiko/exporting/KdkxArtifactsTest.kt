package dev.kodakiko.exporting

import dev.kodakiko.crypto.bytesToHex
import dev.kodakiko.crypto.concatBytes
import dev.kodakiko.crypto.counterBytes
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.testing.GoldenReplay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class KdkxArtifactsTest {
    private val sodium = createSodium()
    private val directory = File(GoldenReplay.repoRoot, "apps/android/build/kotlin-artifacts/export").apply { mkdirs() }

    private fun write(name: String, passphrase: String, plaintextSize: Int, pushSizes: List<Int>) {
        val params = KdkxParams(sodium.randomBytes(KDKX_SALT_BYTES), 2, 8_388_608)
        val key = deriveKdkxKey(sodium, passphrase, params)
        val plaintext = counterBytes(29, plaintextSize)
        val writer = createKdkxWriter(sodium, key, params)
        val parts = mutableListOf(writer.header)
        var cursor = 0
        for (size in pushSizes) {
            parts.addAll(writer.push(plaintext.copyOfRange(cursor, cursor + size)))
            cursor += size
        }
        parts.addAll(writer.push(plaintext.copyOfRange(cursor, plaintext.size)))
        parts.add(writer.finish())
        File(directory, "$name.kdkx").writeBytes(concatBytes(*parts.toTypedArray()))
        File(directory, "$name.artifact.json").writeText(
            buildJsonObject {
                put("kind", "kdkx")
                put("file", "$name.kdkx")
                put("passphrase", passphrase)
                put("opsLimit", params.opsLimit)
                put("memLimitBytes", params.memLimitBytes)
                put("salt", bytesToHex(params.salt))
                put("plaintextSeed", 29)
                put("plaintextSize", plaintextSize)
            }.toString(),
        )
    }

    @Test
    fun writesKdkxArtifactsForTheTypeScriptReverseJudge() {
        write("kdkx-empty", "correct horse battery staple", 0, emptyList())
        write("kdkx-non-ascii", "p\u00e4ssphr\u00e4se \u00e9t\u00e9 \uD83C\uDF38 \uFB01", 1_000, listOf(1, 999))
        write("kdkx-two-chunks-plus-seven", "uneven pushes", 2 * KDKX_CHUNK_BYTES + 7, listOf(5, 1_048_570, 700_000, 300_000))
        assertTrue(File(directory, "kdkx-two-chunks-plus-seven.kdkx").length() > 2L * KDKX_CHUNK_BYTES)
    }
}
