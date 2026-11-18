package dev.kodakiko.exporting

import dev.kodakiko.testing.GoldenReplay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipArchiveTest {
    private val cases = GoldenReplay.clientCases("archive/zips.json")
    private val artifacts = File(GoldenReplay.repoRoot, "apps/android/build/kotlin-artifacts/zip").apply { mkdirs() }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun inputs(case: JsonObject): List<ZipTestFile> = case.getValue("args").jsonObject.getValue("files").jsonArray.map { element ->
        val file = element.jsonObject
        ZipTestFile(file.text("name"), file.text("mtime"), file.getValue("compress").jsonPrimitive.boolean, GoldenReplay.clientBytes(file.getValue("content")))
    }

    private fun listing(entries: List<Pair<String, ByteArray>>): JsonElement = JsonArray(
        entries.sortedBy { it.first }.map { (name, bytes) ->
            JsonObject(mapOf("name" to JsonPrimitive(name), "size" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(GoldenReplay.sha256(bytes))))
        },
    )

    private fun writeCases() = cases.filter { it.text("fn") == "writeArchive" }

    @Test
    fun readsEveryFflateArchiveExactly() {
        val reads = cases.filter { it.text("fn") == "readArchive" }
        assertEquals(11, reads.size)
        for (case in reads) {
            val zip = GoldenReplay.clientBytes(case.getValue("args").jsonObject.getValue("zip"))
            val actual = listing(ZipGoldenAdapter.read(zip))
            assertTrue(GoldenReplay.equivalent(case.getValue("result"), actual), "${case.text("name")}\n expected ${case["result"]}\n actual $actual")
        }
    }

    @Test
    fun writtenArchivesReadBackToTheirFilesAndStoredOnlyArchivesMatchFflateByteForByte() {
        val writes = writeCases()
        assertEquals(8, writes.size)
        for (case in writes) {
            val files = inputs(case)
            val zip = ZipGoldenAdapter.writeUtc(files)
            val back = ZipGoldenAdapter.read(zip)
            assertEquals(files.map { it.name }.sorted(), back.map { it.first }.sorted(), case.text("name"))
            for (file in files) assertContentEquals(file.content, back.single { it.first == file.name }.second, "${case.text("name")} ${file.name}")
            if (files.none { it.compress }) {
                val fflate = GoldenReplay.clientBytes(case.getValue("result").jsonObject.getValue("zip"))
                assertContentEquals(fflate, zip, "${case.text("name")} must equal fflate's bytes")
            }
        }
        assertTrue(writes.any { case -> inputs(case).none { it.compress } })
    }

    @Test
    fun writesArtifactsForTheTypeScriptReverseJudge() {
        for ((index, case) in writeCases().withIndex()) {
            val files = inputs(case)
            val name = "zip-$index"
            File(artifacts, "$name.zip").writeBytes(ZipGoldenAdapter.writeUtc(files))
            File(artifacts, "$name.artifact.json").writeText(
                buildJsonObject {
                    put("kind", "zip")
                    put("file", "$name.zip")
                    put("case", case.text("name"))
                    put(
                        "entries",
                        buildJsonArray {
                            for (file in files) {
                                add(
                                    buildJsonObject {
                                        put("name", file.name)
                                        put("size", file.content.size)
                                        put("sha256", GoldenReplay.sha256(file.content))
                                        put("mtime", file.mtime)
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
        }
    }
}

class ZipTestFile(val name: String, val mtime: String, val compress: Boolean, val content: ByteArray)
