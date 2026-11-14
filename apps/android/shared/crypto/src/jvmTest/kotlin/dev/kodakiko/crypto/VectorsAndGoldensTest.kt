package dev.kodakiko.crypto

import dev.kodakiko.crypto.TestSupport.artifactDirectory
import dev.kodakiko.crypto.TestSupport.goldenDirectory
import dev.kodakiko.crypto.TestSupport.resource
import dev.kodakiko.crypto.TestSupport.sodium
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorsAndGoldensTest {
    private fun cases(file: String): List<JsonObject> =
        Json.parseToJsonElement(File(goldenDirectory, file).readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun sha256(bytes: ByteArray): String = bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun allThirtyEightVectorsPass() {
        val results = checkVectors(sodium) { name -> resource("vectors/$name") }
        assertEquals(38, results.size)
        assertEquals(
            setOf("argon2id.json", "kdf.json", "sealed-blob.json", "padding.json", "stream.json", "recovery-words.json", "share-payload.json"),
            results.map { it.file }.toSet(),
        )
        assertTrue(results.all { it.passed }, results.filterNot { it.passed }.joinToString { "${it.file} ${it.name}" })
    }

    @Test
    fun aTamperedVectorFailsWithoutThrowing() {
        val results = checkVectors(sodium) { name ->
            val text = resource("vectors/$name")
            if (name == "kdf.json") text.replaceFirst("\"subkey\": \"4d", "\"subkey\": \"00") else text
        }
        assertEquals(38, results.size)
        assertEquals(1, results.count { !it.passed })
    }

    @Test
    fun sealedJsonGoldensOpenAndReseal() {
        val golden = cases("crypto/sealed-json.json")
        assertTrue(golden.size >= 6)
        for (case in golden) {
            val context = AdContext.entries.single { it.value == case.text("context") }
            val key = hexToBytes(case.text("key"))
            val blob = hexToBytes(case.text("blob"))
            val padded = openBlob(sodium, key, blob, context, case.text("bindingId"))
            assertEquals(case.text("json"), utf8Decode(unpadIso7816(padded, PADDING_BLOCK_BYTES)), case.text("name"))
            val seededSodium = sodium.withRandomSource(createCounterRandom(case.getValue("randomSeed").jsonPrimitive.int))
            val resealed = sealBlob(seededSodium, key, padIso7816(utf8Encode(case.text("json")), PADDING_BLOCK_BYTES), context, case.text("bindingId"))
            assertContentEquals(blob, resealed, case.text("name"))
        }
    }

    @Test
    fun streamObjectGoldensDecryptAndReencryptByteForByte() {
        val golden = cases("crypto/stream-objects.json")
        assertTrue(golden.map { it.getValue("plaintextSize").jsonPrimitive.long }.containsAll(listOf(0L, 5L, 1_048_577L)))
        for (case in golden) {
            val size = case.getValue("plaintextSize").jsonPrimitive.long
            val key = hexToBytes(case.text("key"))
            val objectBytes = File(goldenDirectory, case.text("file")).readBytes()
            assertEquals(case.text("objectSha256"), sha256(objectBytes))
            val layout = layoutFromCiphertextSize(objectBytes.size.toLong(), STREAM_CHUNK_SIZE_EXPONENT)
            val decryptor = createStreamDecryptor(sodium, key, objectBytes.copyOfRange(0, STREAM_HEADER_BYTES), size)
            val plain = (0 until layout.chunkCount).map { index ->
                val start = STREAM_HEADER_BYTES + index * layout.chunkCiphertextSize
                decryptor.decryptNext(objectBytes.copyOfRange(start.toInt(), minOf(objectBytes.size.toLong(), start + layout.chunkCiphertextSize).toInt()))
            }
            decryptor.finish()
            val plaintext = counterBytes(case.getValue("plaintextSeed").jsonPrimitive.int, size.toInt())
            assertContentEquals(plaintext, concatBytes(*plain.toTypedArray()), case.text("name"))
            val seededSodium = sodium.withRandomSource(createCounterRandom(case.getValue("randomSeed").jsonPrimitive.int))
            val encryptor = createStreamEncryptor(seededSodium, key, size)
            val chunks = (0 until encryptor.layout.chunkCount).map { index ->
                val start = (index * encryptor.layout.chunkSize).toInt()
                encryptor.encryptNext(plaintext.copyOfRange(start, minOf(plaintext.size, start + encryptor.layout.chunkSize)))
            }
            assertEquals(case.text("objectSha256"), sha256(concatBytes(encryptor.header, *chunks.toTypedArray())), case.text("name"))
        }
    }

    @Test
    fun writesArtifactsForTheTypeScriptReverseJudge() {
        val directory = File(artifactDirectory, "crypto").apply { mkdirs() }
        val collectionJson = "{\"schemaVersion\":1,\"name\":\"Kotlin \u00e9t\u00e9\",\"createdAtUtc\":\"2026-11-14T00:00:00.000Z\"}"
        val key = counterBytes(21, 32)
        val blob = sealBlob(sodium, key, padIso7816(utf8Encode(collectionJson), PADDING_BLOCK_BYTES), AdContext.COLLECTION_METADATA, TestSupport.BINDING_ID)
        File(directory, "collection-metadata.artifact.json").writeText(
            buildJsonObject {
                put("kind", "sealed-json")
                put("schema", "CollectionMetadataV1")
                put("key", bytesToHex(key))
                put("context", AdContext.COLLECTION_METADATA.value)
                put("bindingId", TestSupport.BINDING_ID)
                put("blob", bytesToHex(blob))
                put("expectedJson", collectionJson)
            }.toString(),
        )
        for ((name, size) in listOf("empty" to 0, "multi-chunk" to (1 shl 20) + 3)) {
            val plaintext = counterBytes(17, size)
            val encryptor = createStreamEncryptor(sodium, key, size.toLong())
            val chunks = (0 until encryptor.layout.chunkCount).map { index ->
                val start = (index * encryptor.layout.chunkSize).toInt()
                encryptor.encryptNext(plaintext.copyOfRange(start, minOf(plaintext.size, start + encryptor.layout.chunkSize)))
            }
            File(directory, "$name.kdks").writeBytes(concatBytes(encryptor.header, *chunks.toTypedArray()))
            File(directory, "$name.artifact.json").writeText(
                buildJsonObject {
                    put("kind", "stream-object")
                    put("key", bytesToHex(key))
                    put("file", "$name.kdks")
                    put("plaintextSeed", 17)
                    put("plaintextSize", size)
                }.toString(),
            )
        }
        assertTrue(File(directory, "multi-chunk.kdks").length() > (1L shl 20))
    }
}
