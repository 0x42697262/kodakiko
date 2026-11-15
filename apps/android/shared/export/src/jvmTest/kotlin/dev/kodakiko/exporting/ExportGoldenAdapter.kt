package dev.kodakiko.exporting

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ENTRY_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.crypto.bytesToHex
import dev.kodakiko.crypto.concatBytes
import dev.kodakiko.crypto.counterBytes
import dev.kodakiko.crypto.createCounterRandom
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.hexToBytes
import dev.kodakiko.crypto.withRandomSource
import dev.kodakiko.js.JsDate
import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import dev.kodakiko.testing.UnsupportedGoldenFunction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

object ExportGoldenAdapter : GoldenAdapter {
    private const val INVALID_DATE = "Invalid Date"
    private const val STORED_KDKX_DIRECTORY = "export/kdkx"

    private val sodium by lazy { createSodium() }

    private val storedKdkxBySha: Map<String, List<String>> by lazy {
        File(GoldenReplay.goldenDirectory, STORED_KDKX_DIRECTORY).listFiles().orEmpty().filter { it.isFile }
            .groupBy({ GoldenReplay.sha256(it.readBytes()) }, { "$STORED_KDKX_DIRECTORY/${it.name}" })
    }

    private val layoutConstants: Map<String, JsonElement> by lazy {
        linkedMapOf(
            "EXPORT_NOTE_FILE" to JsonPrimitive(EXPORT_NOTE_FILE),
            "EXPORT_README_FILE" to JsonPrimitive(EXPORT_README_FILE),
            "EXPORT_MISSING_FILE" to JsonPrimitive(EXPORT_MISSING_FILE),
            "EXPORT_FALLBACK_EXTENSION" to JsonPrimitive(EXPORT_FALLBACK_EXTENSION),
            "EXPORT_EXTENSIONS" to JsonObject(EXPORT_EXTENSIONS.mapValues { JsonPrimitive(it.value) }),
        )
    }

    private val zipConstants: Map<String, JsonElement> by lazy {
        linkedMapOf(
            "EXPORT_FILE_OVERHEAD_BYTES" to JsonPrimitive(EXPORT_FILE_OVERHEAD_BYTES),
            "EXPORT_PART_MAX_FILES" to JsonPrimitive(EXPORT_PART_MAX_FILES),
            "EXPORT_PART_MAX_BYTES" to JsonPrimitive(EXPORT_PART_MAX_BYTES),
            "EXPORT_WEB_MEMORY_PART_MAX_BYTES" to JsonPrimitive(EXPORT_WEB_MEMORY_PART_MAX_BYTES),
            "EXPORT_MTIME_MIN" to date(EXPORT_MTIME_MIN),
            "EXPORT_MTIME_MAX" to date(EXPORT_MTIME_MAX),
        )
    }

    private val kdkxConstants: Map<String, JsonElement> by lazy {
        linkedMapOf(
            "KDKX_EXTENSION" to JsonPrimitive(KDKX_EXTENSION),
            "KDKX_CHUNK_BYTES" to JsonPrimitive(KDKX_CHUNK_BYTES),
            "KDKX_SALT_BYTES" to JsonPrimitive(KDKX_SALT_BYTES),
        )
    }

    override fun call(fn: String, args: JsonObject): JsonElement = when (fn) {
        "planEntryExport" -> plan(planEntryExport(args.string("entryId"), metadata(args.getValue("metadata").jsonObject)))
        "frontMatter" -> JsonPrimitive(frontMatter(args.string("entryId"), metadata(args.getValue("metadata").jsonObject)))
        "yamlQuote" -> JsonPrimitive(yamlQuote(args.string("value")))
        "EXPORT_TEXT.readme" -> JsonPrimitive(ExportText.readme(args.string("date")))
        "EXPORT_TEXT.missingHeader" -> JsonPrimitive(ExportText.MISSING_HEADER)
        "EXPORT_TEXT.missingLine" -> JsonPrimitive(ExportText.missingLine(args.string("folder"), args.string("file"), args.string("reason")))
        "layout.constants" -> JsonObject(layoutConstants)
        "zip.constants" -> JsonObject(zipConstants)
        "kdkx.constants" -> JsonObject(kdkxConstants)
        "parseEntryNote" -> parsed(parseEntryNote(args.string("source")))
        "planParts" -> JsonArray(
            planParts(args.getValue("files").jsonArray.map { plannedFile(it.jsonObject) }, args.long("partMaxBytes"), args.long("partMaxFiles"))
                .map { part -> JsonArray(part.map(::plannedFile)) },
        )
        "partFileName" -> JsonPrimitive(partFileName(args.string("exportDate"), args.long("partNumber")))
        "clampZipDate" -> date(clampZipDate(JsDate.parse(args.string("date"))))
        "createKdkxWriter" -> writeKdkx(args)
        "deriveKdkxKey" -> JsonPrimitive(bytesToHex(deriveKdkxKey(sodium, args.string("passphrase"), params(args.getValue("params").jsonObject))))
        "readKdkxParams" -> params(readKdkxParams(input(args)))
        "isKdkx" -> JsonPrimitive(isKdkx(input(args)))
        "decryptKdkx" -> {
            val file = args.getValue("mutations").jsonArray.fold(GoldenReplay.bytes(args.getValue("file"))) { bytes, mutation ->
                mutate(bytes, mutation.jsonObject)
            }
            content(decryptKdkx(sodium, hexToBytes(args.string("key")), file))
        }
        else -> throw UnsupportedGoldenFunction(fn)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.nullableString(key: String): String? = getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun number(value: Double): JsonElement = when {
        value.isNaN() -> JsonObject(mapOf("\$number" to JsonPrimitive("NaN")))
        value.isInfinite() -> JsonObject(mapOf("\$number" to JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")))
        else -> JsonPrimitive(value + 0.0)
    }

    private fun date(time: Double): JsonElement = JsonPrimitive(if (time.isNaN()) INVALID_DATE else JsDate.toIsoString(time))

    private fun location(json: JsonElement): EntryLocation? = (json as? JsonObject)?.let {
        EntryLocation(
            latitude = it.getValue("latitude").jsonPrimitive.double,
            longitude = it.getValue("longitude").jsonPrimitive.double,
            accuracyMeters = it.getValue("accuracyMeters").jsonPrimitive.doubleOrNull,
            source = LocationSource.entries.first { source -> source.wire == it.string("source") },
        )
    }

    private fun metadata(json: JsonObject): EntryMetadataV1 {
        check(json.int("schemaVersion") == ENTRY_METADATA_SCHEMA_VERSION) { "metadata has an unsupported schemaVersion" }
        return EntryMetadataV1(
            takenAtUtc = json.string("takenAtUtc"),
            utcOffsetMinutes = json.long("utcOffsetMinutes"),
            timeZone = json.nullableString("timeZone"),
            note = json.string("note"),
            tags = json.strings("tags"),
            location = location(json.getValue("location")),
            media = json.getValue("media").jsonArray.map(ContractSchemas.mediaDescriptorV1::decode),
            createdAtUtc = json.string("createdAtUtc"),
            editedAtUtc = json.string("editedAtUtc"),
            favorite = json["favorite"]?.jsonPrimitive?.boolean,
        )
    }

    private fun plan(plan: ExportEntryPlan): JsonElement = JsonObject(
        mapOf(
            "folder" to JsonPrimitive(plan.folder),
            "noteFile" to JsonPrimitive(plan.noteFile),
            "noteText" to JsonPrimitive(plan.noteText),
            "media" to JsonArray(
                plan.media.map {
                    JsonObject(
                        mapOf(
                            "mediaId" to JsonPrimitive(it.mediaId),
                            "fileName" to JsonPrimitive(it.fileName),
                            "plaintextBytes" to JsonPrimitive(it.plaintextBytes),
                        ),
                    )
                },
            ),
            "mtime" to date(plan.mtime),
        ),
    )

    private fun parsed(note: ParsedEntryNote): JsonElement = JsonObject(
        mapOf(
            "id" to JsonPrimitive(note.id),
            "takenAtUtc" to JsonPrimitive(note.takenAtUtc),
            "utcOffsetMinutes" to JsonPrimitive(note.utcOffsetMinutes),
            "tags" to JsonArray(note.tags.map(::JsonPrimitive)),
            "favorite" to JsonPrimitive(note.favorite),
            "location" to (
                note.location?.let {
                    JsonObject(mapOf("latitude" to number(it.latitude), "longitude" to number(it.longitude), "source" to JsonPrimitive(it.source)))
                } ?: JsonNull
                ),
            "media" to JsonArray(
                note.media.map { item ->
                    val fields = linkedMapOf<String, JsonElement>(
                        "file" to JsonPrimitive(item.file),
                        "originalName" to JsonPrimitive(item.originalName),
                        "kind" to JsonPrimitive(item.kind.wire),
                    )
                    item.caption?.let { fields["caption"] = JsonPrimitive(it) }
                    JsonObject(fields)
                },
            ),
            "note" to JsonPrimitive(note.note),
        ),
    )

    private fun plannedFile(json: JsonObject): PlannedFile = PlannedFile(json.string("entryId"), json.string("path"), json.long("bytes"))

    private fun plannedFile(file: PlannedFile): JsonElement = JsonObject(
        mapOf("entryId" to JsonPrimitive(file.entryId), "path" to JsonPrimitive(file.path), "bytes" to JsonPrimitive(file.bytes)),
    )

    private fun params(json: JsonObject): KdkxParams = KdkxParams(hexToBytes(json.string("salt")), json.long("opsLimit"), json.long("memLimitBytes"))

    private fun params(params: KdkxParams): JsonElement = JsonObject(
        mapOf(
            "opsLimit" to JsonPrimitive(params.opsLimit),
            "memLimitBytes" to JsonPrimitive(params.memLimitBytes),
            "salt" to JsonPrimitive(bytesToHex(params.salt)),
        ),
    )

    private fun input(args: JsonObject): ByteArray = args["file"]?.let(GoldenReplay::bytes) ?: hexToBytes(args.string("bytes"))

    private fun content(bytes: ByteArray): JsonElement =
        JsonObject(mapOf("size" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(GoldenReplay.sha256(bytes))))

    private fun fileReference(bytes: ByteArray): JsonElement {
        val sha256 = GoldenReplay.sha256(bytes)
        val stored = storedKdkxBySha[sha256]?.singleOrNull() ?: return content(bytes)
        return JsonObject(mapOf("file" to JsonPrimitive(stored), "size" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(sha256)))
    }

    private fun mutate(bytes: ByteArray, mutation: JsonObject): ByteArray = when (val op = mutation.string("op")) {
        "xor" -> bytes.copyOf().also {
            val offset = mutation.int("offset")
            it[offset] = (it[offset].toInt() xor mutation.int("value")).toByte()
        }
        "truncate" -> bytes.copyOf(mutation.int("length"))
        "set" -> bytes.copyOf().also { hexToBytes(mutation.string("bytes")).copyInto(it, mutation.int("offset")) }
        "append" -> concatBytes(bytes, hexToBytes(mutation.string("bytes")))
        else -> error("unknown mutation $op")
    }

    private class RecordingRandom(seed: Int) : RandomSource {
        private val source = createCounterRandom(seed)
        val draws = mutableListOf<Int>()

        override fun next(length: Int): ByteArray {
            draws.add(length)
            return source.next(length)
        }
    }

    private fun writeKdkx(args: JsonObject): JsonElement {
        val random = RecordingRandom(args.int("randomSeed"))
        val goldenSodium = sodium.withRandomSource(random)
        val plaintext = counterBytes(args.int("plaintextSeed"), args.int("plaintextSize"))
        val kdf = args.getValue("kdf").jsonObject
        val salt = goldenSodium.randomBytes(KDKX_SALT_BYTES)
        val params = KdkxParams(salt, kdf.long("opsLimit"), kdf.long("memLimitBytes"))
        val key = deriveKdkxKey(goldenSodium, args.string("passphrase"), params)
        val writer = createKdkxWriter(goldenSodium, key, params)
        val chunks = mutableListOf(writer.header)
        var cursor = 0
        val operations = args.getValue("operations").jsonArray.map { operation ->
            val fields = operation.jsonObject
            try {
                when (val op = fields.string("op")) {
                    "push" -> {
                        val size = fields.int("size")
                        val piece = plaintext.copyOfRange(cursor, cursor + size)
                        cursor += size
                        val pushed = writer.push(piece)
                        chunks.addAll(pushed)
                        JsonObject(mapOf("chunkSizes" to JsonArray(pushed.map { JsonPrimitive(it.size) })))
                    }
                    "finish" -> writer.finish().let {
                        chunks.add(it)
                        JsonObject(mapOf("chunkSize" to JsonPrimitive(it.size)))
                    }
                    else -> error("unknown operation $op")
                }
            } catch (thrown: Exception) {
                GoldenReplay.error(thrown)
            }
        }
        return JsonObject(
            mapOf(
                "randomDraws" to JsonArray(random.draws.map(::JsonPrimitive)),
                "salt" to JsonPrimitive(bytesToHex(salt)),
                "key" to JsonPrimitive(bytesToHex(key)),
                "header" to JsonPrimitive(bytesToHex(writer.header)),
                "operations" to JsonArray(operations),
                "file" to fileReference(concatBytes(*chunks.toTypedArray())),
            ),
        )
    }
}
