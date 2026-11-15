package dev.kodakiko.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

fun interface GoldenAdapter {
    fun call(fn: String, args: JsonObject): JsonElement
}

class UnsupportedGoldenFunction(fn: String) : Exception("no Kotlin call for golden fn '$fn'")

class GoldenFailure(message: String) : AssertionError(message)

object GoldenReplay {
    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            ?: error("repository root not found")
    }

    val goldenDirectory: File get() = File(repoRoot, "tests/fixtures/golden")

    fun cases(path: String): List<JsonObject> =
        Json.parseToJsonElement(File(goldenDirectory, path).readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    fun undefined(): JsonElement = JsonObject(mapOf("\$undefined" to JsonPrimitive(true)))

    fun bytes(reference: JsonElement): ByteArray {
        val obj = reference.jsonObject
        val content = obj["file"]?.let { File(goldenDirectory, it.jsonPrimitive.content).readBytes() }
            ?: obj.getValue("segments").jsonArray.fold(ByteArray(0)) { acc, segment ->
                val part = segment.jsonObject
                val size = part.getValue("size").jsonPrimitive.content.toInt()
                acc + (
                    part["file"]?.let { File(goldenDirectory, it.jsonPrimitive.content).readBytes() }
                        ?: ByteArray(size) { part.getValue("fill").jsonPrimitive.content.toInt().toByte() }
                    )
            }
        val expected = obj.getValue("sha256").jsonPrimitive.content
        val actual = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "binary reference ${obj["file"] ?: "segments"} has sha256 $actual, expected $expected" }
        return content
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun error(throwable: Throwable): JsonElement = JsonObject(
        mapOf(
            "error" to JsonPrimitive(throwable::class.simpleName ?: "Error"),
            "message" to JsonPrimitive(throwable.message ?: ""),
        ),
    )

    fun equivalent(expected: JsonElement, actual: JsonElement): Boolean = when {
        expected is JsonNull || actual is JsonNull -> expected is JsonNull && actual is JsonNull
        expected is JsonObject && actual is JsonObject ->
            expected.keys == actual.keys && expected.all { (key, value) -> equivalent(value, actual.getValue(key)) }
        expected is JsonArray && actual is JsonArray ->
            expected.size == actual.size && expected.indices.all { equivalent(expected[it], actual[it]) }
        expected is JsonPrimitive && actual is JsonPrimitive -> primitivesEqual(expected, actual)
        else -> false
    }

    private fun primitivesEqual(expected: JsonPrimitive, actual: JsonPrimitive): Boolean {
        if (expected.isString || actual.isString) return expected.isString == actual.isString && expected.content == actual.content
        val left = expected.content.toBigDecimalOrNull()
        val right = actual.content.toBigDecimalOrNull()
        return if (left != null && right != null) left.compareTo(right) == 0 else expected.content == actual.content
    }

    fun run(path: String, adapter: GoldenAdapter) {
        val cases = cases(path)
        check(cases.isNotEmpty()) { "$path has no cases" }
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val fn = case.getValue("fn").jsonPrimitive.content
            val expected = case.getValue("result")
            val actual = try {
                adapter.call(fn, case.getValue("args").jsonObject)
            } catch (unsupported: UnsupportedGoldenFunction) {
                throw GoldenFailure("$path: ${unsupported.message} (case '$name')")
            } catch (thrown: Throwable) {
                error(thrown)
            }
            if (!equivalent(expected, actual)) failures += "$name\n    expected: $expected\n    actual:   $actual"
        }
        if (failures.isNotEmpty()) {
            throw GoldenFailure("$path: ${failures.size} of ${cases.size} cases differ\n" + failures.joinToString("\n"))
        }
    }
}
