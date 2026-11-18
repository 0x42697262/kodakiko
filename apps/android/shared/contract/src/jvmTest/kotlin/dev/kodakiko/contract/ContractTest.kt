package dev.kodakiko.contract

import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.MalformedPayloadError
import dev.kodakiko.crypto.UnsupportedFormatError
import dev.kodakiko.crypto.bytesToHex
import dev.kodakiko.crypto.counterBytes
import dev.kodakiko.crypto.createCounterRandom
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.hexToBytes
import dev.kodakiko.crypto.withRandomSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ContractTest {
    private val sodium = createSodium()
    private val bindingId = "AAAAAAAAAAAAAAAAAAAAAA"
    private val key = counterBytes(1, 32)

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }

    private fun golden(path: String): List<JsonObject> =
        Json.parseToJsonElement(File(repoRoot, "tests/fixtures/golden/$path").readText())
            .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private inline fun <reified T : Throwable> catching(action: () -> Unit): T? =
        try {
            action()
            null
        } catch (error: Throwable) {
            error as? T ?: throw AssertionError("expected ${T::class.simpleName} but got $error", error)
        }

    @Suppress("UNCHECKED_CAST")
    private fun schema(name: String): ContractSchema<Any?> =
        (ContractSchemas.all[name] ?: fail("no Kotlin schema for $name")) as ContractSchema<Any?>

    @Test
    fun everyTypeScriptSchemaHasAKotlinSchema() {
        val names = golden("contract/dto-samples.json").map { it.text("schema") }.toSet()
        assertEquals(79, names.size)
        assertEquals(names, ContractSchemas.all.keys)
    }

    @Test
    fun validSamplesDecodeAndRoundTripInvalidSamplesAreRejected() {
        val problems = mutableListOf<String>()
        for (case in golden("contract/dto-samples.json")) {
            val name = case.text("schema")
            val schema = schema(name)
            for (sample in case.getValue("valid").jsonArray) {
                try {
                    val decoded = schema.decode(sample)
                    val again = schema.decode(schema.encode(decoded))
                    if (again != decoded) problems += "$name round trip changed $sample"
                } catch (error: ContractViolation) {
                    problems += "$name rejected valid $sample: ${error.message}"
                }
            }
            for (sample in case.getValue("invalid").jsonArray) {
                val accepted = try {
                    schema.decode(sample)
                    true
                } catch (error: ContractViolation) {
                    false
                }
                if (accepted) problems += "$name accepted invalid $sample"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun sealedPayloadGoldensEncodeByteForByteAndOpen() {
        for (case in golden("crypto/sealed-json.json")) {
            val name = case.text("name")
            val schema = schema(case.text("schema"))
            val decoded = schema.decode(Json.parseToJsonElement(case.text("json")))
            assertEquals(case.text("json"), ContractJson.stringify(schema.encode(decoded)), name)
            val context = AdContext.entries.single { it.value == case.text("context") }
            val seeded = sodium.withRandomSource(createCounterRandom(case.getValue("randomSeed").jsonPrimitive.int))
            val blob = sealPayload(seeded, hexToBytes(case.text("key")), decoded, schema, context, case.text("bindingId"))
            assertEquals(case.text("blob"), bytesToHex(blob), name)
            val opened = openPayload(sodium, hexToBytes(case.text("key")), hexToBytes(case.text("blob")), schema, 1, emptyMap(), context, case.text("bindingId"))
            assertEquals(decoded, opened, name)
        }
    }

    private class VersionedSchema : ContractSchema<Pair<Int, String>> {
        override val name = "test.versioned"

        override fun decode(json: JsonElement): Pair<Int, String> {
            val obj = json as? JsonObject ?: throw ContractViolation(name, "not an object")
            val version = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw ContractViolation(name, "schemaVersion")
            val value = (obj["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ContractViolation(name, "value")
            if (version != 2) throw ContractViolation(name, "schemaVersion")
            return version to value
        }

        override fun encode(value: Pair<Int, String>): JsonElement = buildJsonObject {
            put("schemaVersion", value.first)
            put("value", value.second)
        }
    }

    private class RawSchema : ContractSchema<JsonElement> {
        override val name = "test.raw"

        override fun decode(json: JsonElement): JsonElement = json

        override fun encode(value: JsonElement): JsonElement = value
    }

    private fun oldPayload() = buildJsonObject {
        put("schemaVersion", 1)
        put("text", "x")
    }

    private fun mismatchedPayload() = buildJsonObject {
        put("schemaVersion", 2)
        put("value", 5)
    }

    @Test
    fun openPayloadMigratesOlderVersions() {
        val versioned = VersionedSchema()
        val migrations = mapOf(
            1 to { raw: JsonObject ->
                buildJsonObject {
                    put("schemaVersion", 2)
                    put("value", raw.getValue("text"))
                }
            },
        )
        val current = sealPayload(sodium, key, 2 to "hello", versioned, AdContext.ENTRY_METADATA, bindingId)
        assertEquals(2 to "hello", openPayload(sodium, key, current, versioned, 2, migrations, AdContext.ENTRY_METADATA, bindingId))
        val old = sealPayload(sodium, key, oldPayload(), RawSchema(), AdContext.ENTRY_METADATA, bindingId)
        assertEquals(2 to "x", openPayload(sodium, key, old, versioned, 2, migrations, AdContext.ENTRY_METADATA, bindingId))
    }

    @Test
    fun openPayloadFailures() {
        val versioned = VersionedSchema()
        val context = AdContext.USER_SETTINGS
        val newer = sealPayload(sodium, key, buildJsonObject { put("schemaVersion", 3) }, RawSchema(), context, bindingId)
        assertNotNull(catching<UnsupportedFormatError> { openPayload(sodium, key, newer, versioned, 2, emptyMap(), context, bindingId) })
        val old = sealPayload(sodium, key, oldPayload(), RawSchema(), context, bindingId)
        assertNotNull(catching<UnsupportedFormatError> { openPayload(sodium, key, old, versioned, 2, emptyMap(), context, bindingId) })
        val mismatch = sealPayload(sodium, key, mismatchedPayload(), RawSchema(), context, bindingId)
        assertNotNull(catching<MalformedPayloadError> { openPayload(sodium, key, mismatch, versioned, 2, emptyMap(), context, bindingId) })
    }

    @Test
    fun base64UrlMatchesTheContractEncoding() {
        assertEquals("AQID", encodeBytes(byteArrayOf(1, 2, 3)))
        assertEquals("_-8", encodeBytes(byteArrayOf(0xff.toByte(), 0xef.toByte())))
        assertContentEquals(byteArrayOf(1, 2, 3), decodeBytes("AQID"))
        for (bad in listOf("AQID=", "A", "AQ+D", "AQ/D", "AQ D", "")) {
            assertNotNull(catching<ContractViolation> { decodeBytes(bad) }, bad)
        }
        assertTrue(isValidId("AQIDBAUGBwgJCgsMDQ4PEA"))
        assertTrue(!isValidId("AQIDBAUGBwgJCgsMDQ4PE"))
        assertTrue(!isValidId("AQIDBAUGBwgJCgsMDQ4PEA="))
        assertEquals(22, encodeBytes(ByteArray(16)).length)
    }

    @Test
    fun stringifyMatchesJavaScriptJsonStringify() {
        val element = buildJsonObject {
            put("a", "line\nbreak \u0001 \"quoted\" \\ slash / \u00e9 \u2028")
            put("n", 5)
            put("f", 1.5)
            put("z", JsonPrimitive(null as String?))
        }
        assertEquals("{\"a\":\"line\\nbreak \\u0001 \\\"quoted\\\" \\\\ slash / \u00e9 \u2028\",\"n\":5,\"f\":1.5,\"z\":null}", ContractJson.stringify(element))
    }

    @Test
    fun writesDtoAndSealedArtifactsForTheTypeScriptReverseJudge() {
        val directory = File(repoRoot, "apps/android/build/kotlin-artifacts/contract").apply { mkdirs() }
        for (case in golden("contract/dto-samples.json")) {
            val name = case.text("schema")
            val schema = schema(name)
            val encoded = ContractJson.stringify(schema.encode(schema.decode(case.getValue("valid").jsonArray.first())))
            File(directory, "dto-$name.artifact.json").writeText(
                buildJsonObject {
                    put("kind", "dto")
                    put("schema", name)
                    put("json", encoded)
                }.toString(),
            )
        }
        val entryCase = golden("crypto/sealed-json.json").first { it.text("name") == "entry-full" }
        val entrySchema = schema("EntryMetadataV1")
        val entry = entrySchema.decode(Json.parseToJsonElement(entryCase.text("json")))
        val entryKey = counterBytes(40, 32)
        val blob = sealPayload(sodium, entryKey, entry, entrySchema, AdContext.ENTRY_METADATA, entryCase.text("bindingId"))
        File(directory, "entry-full.artifact.json").writeText(
            buildJsonObject {
                put("kind", "sealed-json")
                put("schema", "EntryMetadataV1")
                put("key", bytesToHex(entryKey))
                put("context", AdContext.ENTRY_METADATA.value)
                put("bindingId", entryCase.text("bindingId"))
                put("blob", bytesToHex(blob))
                put("expectedJson", entryCase.text("json"))
            }.toString(),
        )
        assertTrue(File(directory, "entry-full.artifact.json").isFile)
    }
}
