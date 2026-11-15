package dev.kodakiko.index

import dev.kodakiko.contract.WireEnum
import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import dev.kodakiko.testing.UnsupportedGoldenFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object IndexGoldenAdapter : GoldenAdapter {
    private const val GALLERY_INDEX_PREFIX = "GalleryIndex."

    private val galleryIndexMethods = setOf(
        "upsert", "remove", "clear", "get", "size", "version", "query", "allTags", "tagSuggestions", "onThisDay", "daysWithEntries",
    )

    override fun call(fn: String, args: JsonObject): JsonElement = when {
        fn == "tokenize" -> strings(tokenize(args.string("text")))
        fn == "sortEntryIds" -> {
            val entries = args.getValue("entries").jsonArray.map { entry(it.jsonObject) }
            val get: (String) -> IndexedEntry? = { id -> entries.firstOrNull { it.id == id } }
            strings(sortEntryIds(args.strings("ids"), get, sort(args.getValue("sort").jsonObject), args.getValue("seed").jsonPrimitive.double))
        }
        fn == "attributesMatch" -> JsonPrimitive(
            attributesMatch(attributeEntry(args.getValue("entry").jsonObject), filters(args.getValue("filters").jsonObject), args.string("homeCollectionId")),
        )
        fn.startsWith(GALLERY_INDEX_PREFIX) && fn.removePrefix(GALLERY_INDEX_PREFIX) in galleryIndexMethods -> driveGalleryIndex(args)
        else -> throw UnsupportedGoldenFunction(fn)
    }

    private fun driveGalleryIndex(args: JsonObject): JsonElement {
        val index = GalleryIndex(args.string("homeCollectionId"))
        args["setupFile"]?.let { reference ->
            Json.parseToJsonElement(GoldenReplay.bytes(reference).decodeToString()).jsonArray.forEach { step(index, it.jsonObject) }
        }
        args.getValue("setup").jsonArray.forEach { step(index, it.jsonObject) }
        return JsonArray(args.getValue("calls").jsonArray.map { step(index, it.jsonObject) })
    }

    private fun step(index: GalleryIndex, step: JsonObject): JsonElement {
        val method = step.string("method")
        val args = step.getValue("args").jsonArray
        fun text(position: Int): String = args[position].jsonPrimitive.content
        return when (method) {
            "upsert" -> {
                index.upsert(entry(args[0].jsonObject), text(1))
                JsonNull
            }
            "remove" -> {
                index.remove(text(0))
                JsonNull
            }
            "clear" -> {
                index.clear()
                JsonNull
            }
            "get" -> index.get(text(0))?.let(::entryJson) ?: JsonNull
            "size" -> JsonPrimitive(index.size())
            "version" -> JsonPrimitive(index.version())
            "query" -> JsonArray(index.query(filter(args[0].jsonObject)).map(::section))
            "allTags" -> JsonArray(index.allTags().map { JsonObject(mapOf("tag" to JsonPrimitive(it.tag), "count" to JsonPrimitive(it.count))) })
            "tagSuggestions" -> strings(index.tagSuggestions(text(0), args[1].jsonPrimitive.int))
            "onThisDay" -> JsonArray(
                index.onThisDay(text(0), scope(args[1].jsonObject)).map {
                    JsonObject(mapOf("yearsAgo" to number(it.yearsAgo), "entryIds" to strings(it.entryIds)))
                },
            )
            "daysWithEntries" -> JsonArray(
                index.daysWithEntries(text(0), scope(args[1].jsonObject)).map { (localDate, entryId) ->
                    JsonArray(listOf(JsonPrimitive(localDate), JsonPrimitive(entryId)))
                },
            )
            else -> throw UnsupportedGoldenFunction(GALLERY_INDEX_PREFIX + method)
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.nullableString(key: String): String? = getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun <E : WireEnum> wire(entries: List<E>, value: String): E = entries.first { it.wire == value }

    private fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun number(value: Double): JsonElement = when {
        value.isNaN() -> JsonObject(mapOf("\$number" to JsonPrimitive("NaN")))
        value.isInfinite() -> JsonObject(mapOf("\$number" to JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")))
        else -> JsonPrimitive(value)
    }

    private fun entry(json: JsonObject): IndexedEntry = IndexedEntry(
        id = json.string("id"),
        isOwn = json.getValue("isOwn").jsonPrimitive.boolean,
        collectionIds = json.strings("collectionIds"),
        trashed = json.getValue("trashed").jsonPrimitive.boolean,
        unreadable = json.getValue("unreadable").jsonPrimitive.boolean,
        takenAtUtc = json.string("takenAtUtc"),
        utcOffsetMinutes = json.getValue("utcOffsetMinutes").jsonPrimitive.long,
        localDate = json.string("localDate"),
        monthDay = json.string("monthDay"),
        tags = json.strings("tags"),
        coverMediaId = json.nullableString("coverMediaId"),
        mediaCount = json.getValue("mediaCount").jsonPrimitive.int,
        hasVideo = json.getValue("hasVideo").jsonPrimitive.boolean,
        notePreview = json.string("notePreview"),
        pending = json.getValue("pending").jsonPrimitive.boolean,
        favorite = json["favorite"]?.jsonPrimitive?.boolean,
        videoCount = json["videoCount"]?.jsonPrimitive?.int,
        videoMs = json["videoMs"]?.jsonPrimitive?.long,
        originalBytes = json["originalBytes"]?.jsonPrimitive?.long,
    )

    private fun attributeEntry(json: JsonObject): IndexedEntry = IndexedEntry(
        id = "",
        isOwn = json.getValue("isOwn").jsonPrimitive.boolean,
        collectionIds = json.strings("collectionIds"),
        trashed = false,
        unreadable = false,
        takenAtUtc = "",
        utcOffsetMinutes = 0,
        localDate = "",
        monthDay = "",
        tags = emptyList(),
        coverMediaId = null,
        mediaCount = json.getValue("mediaCount").jsonPrimitive.int,
        hasVideo = json.getValue("hasVideo").jsonPrimitive.boolean,
        notePreview = json.string("notePreview"),
        pending = json.getValue("pending").jsonPrimitive.boolean,
        favorite = json["favorite"]?.jsonPrimitive?.boolean,
    )

    private fun entryJson(entry: IndexedEntry): JsonElement = JsonObject(
        buildMap {
            put("id", JsonPrimitive(entry.id))
            put("isOwn", JsonPrimitive(entry.isOwn))
            put("collectionIds", strings(entry.collectionIds))
            put("trashed", JsonPrimitive(entry.trashed))
            put("unreadable", JsonPrimitive(entry.unreadable))
            put("tags", strings(entry.tags))
            put("coverMediaId", JsonPrimitive(entry.coverMediaId))
            put("mediaCount", JsonPrimitive(entry.mediaCount))
            put("hasVideo", JsonPrimitive(entry.hasVideo))
            put("notePreview", JsonPrimitive(entry.notePreview))
            put("pending", JsonPrimitive(entry.pending))
            put("takenAtUtc", JsonPrimitive(entry.takenAtUtc))
            put("utcOffsetMinutes", JsonPrimitive(entry.utcOffsetMinutes))
            put("localDate", JsonPrimitive(entry.localDate))
            put("monthDay", JsonPrimitive(entry.monthDay))
            entry.favorite?.let { put("favorite", JsonPrimitive(it)) }
            entry.videoCount?.let { put("videoCount", JsonPrimitive(it)) }
            entry.videoMs?.let { put("videoMs", JsonPrimitive(it)) }
            entry.originalBytes?.let { put("originalBytes", JsonPrimitive(it)) }
        },
    )

    private fun scope(json: JsonObject): GalleryScope = when (val kind = json.string("kind")) {
        "home" -> GalleryScope.Home
        "collection" -> GalleryScope.Collection(json.string("collectionId"))
        else -> error("unknown scope kind $kind")
    }

    private fun filters(json: JsonObject): AttributeFilters =
        json.entries.associate { (key, state) -> wire(AttributeKey.entries, key) to wire(TriState.entries, state.jsonPrimitive.content) }

    private fun filter(json: JsonObject): GalleryFilter = GalleryFilter(
        scope = scope(json.getValue("scope").jsonObject),
        view = wire(GalleryView.entries, json.string("view")),
        tags = json.strings("tags"),
        fromDate = json.nullableString("fromDate"),
        toDate = json.nullableString("toDate"),
        text = json.string("text"),
        attributes = json["attributes"]?.jsonObject?.let(::filters),
    )

    private fun sort(json: JsonObject): GallerySort =
        GallerySort(wire(GallerySortKey.entries, json.string("key")), json.getValue("descending").jsonPrimitive.boolean)

    private fun section(section: DaySection): JsonElement =
        JsonObject(mapOf("localDate" to JsonPrimitive(section.localDate), "entryIds" to strings(section.entryIds)))
}
