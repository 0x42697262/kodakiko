package dev.kodakiko.domain

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MIN_OBJECT_BYTES
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.WireEnum
import dev.kodakiko.crypto.STREAM_MIN_OBJECT_BYTES
import dev.kodakiko.media.GeoPoint
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import dev.kodakiko.testing.GoldenAdapter
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

object DomainGoldenAdapter : GoldenAdapter {
    override fun call(fn: String, args: JsonObject): JsonElement = when (fn) {
        "localDateOf" -> JsonPrimitive(localDateOf(args.string("takenAtUtc"), args.long("utcOffsetMinutes")))
        "localTimeOf" -> JsonPrimitive(localTimeOf(args.string("takenAtUtc"), args.long("utcOffsetMinutes")))
        "localPartsToUtc" -> JsonPrimitive(localPartsToUtc(args.string("date"), args.string("time"), args.long("utcOffsetMinutes")))
        "monthGrid" -> {
            val weekStart = args.getValue("weekStartsOn").jsonPrimitive.int
            val grid = monthGrid(args.string("yearMonth"), args.string("todayLocalDate"), WeekStart.entries.first { it.dayIndex == weekStart })
            JsonArray(grid.map { row -> JsonArray(row.map(::cell)) })
        }
        "addMonths" -> JsonPrimitive(addMonths(args.string("yearMonth"), args.long("delta")))
        "yearMonthOf" -> JsonPrimitive(yearMonthOf(args.string("localDate")))
        "isValidYearMonth" -> JsonPrimitive(isValidYearMonth(args.string("value")))
        "normalizeTag" -> JsonPrimitive(normalizeTag(args.string("input")))
        "sortTags" -> strings(sortTags(args.strings("tags")))
        "addTag" -> {
            val result = addTag(args.strings("tags"), args.string("raw"))
            JsonObject(mapOf("tags" to strings(result.tags), "rejected" to JsonPrimitive(result.rejected?.wire)))
        }
        "removeTag" -> strings(removeTag(args.strings("tags"), args.string("tag")))
        "buildNewEntryMetadata" -> ContractSchemas.entryMetadataV1.encode(buildNewEntryMetadata(draft(args.getValue("draft").jsonObject)))
        "applyEntryEdit" -> ContractSchemas.entryMetadataV1.encode(
            applyEntryEdit(
                ContractSchemas.entryMetadataV1.decode(args.getValue("current")),
                edit(args.getValue("edit").jsonObject),
                args.string("nowUtc"),
            ),
        )
        "MIN_OBJECT_BYTES" -> JsonObject(
            mapOf("MIN_OBJECT_BYTES" to JsonPrimitive(MIN_OBJECT_BYTES), "STREAM_MIN_OBJECT_BYTES" to JsonPrimitive(STREAM_MIN_OBJECT_BYTES)),
        )
        else -> throw UnsupportedGoldenFunction(fn)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun <E : WireEnum> wire(entries: List<E>, value: String): E = entries.first { it.wire == value }

    private fun isNull(element: JsonElement): Boolean = element is JsonNull

    private fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun cell(cell: CalendarCell): JsonElement = JsonObject(
        mapOf(
            "localDate" to JsonPrimitive(cell.localDate),
            "inMonth" to JsonPrimitive(cell.inMonth),
            "isToday" to JsonPrimitive(cell.isToday),
        ),
    )

    private fun point(element: JsonElement): GeoPoint? =
        if (isNull(element)) null else GeoPoint(element.jsonObject.getValue("latitude").jsonPrimitive.double, element.jsonObject.getValue("longitude").jsonPrimitive.double)

    private fun location(element: JsonElement): EntryLocation? {
        if (isNull(element)) return null
        val fields = element.jsonObject
        return EntryLocation(
            latitude = fields.getValue("latitude").jsonPrimitive.double,
            longitude = fields.getValue("longitude").jsonPrimitive.double,
            accuracyMeters = fields.getValue("accuracyMeters").jsonPrimitive.doubleOrNull,
            source = wire(LocationSource.entries, fields.string("source")),
        )
    }

    private fun resolvedTime(time: JsonObject): ResolvedTime =
        ResolvedTime(time.string("capturedAtUtc"), time.long("utcOffsetMinutes"), wire(TimeSource.entries, time.string("source")))

    private fun draft(draft: JsonObject): EntryDraft = EntryDraft(
        media = draft.getValue("media").jsonArray.map(ContractSchemas.mediaDescriptorV1::decode),
        times = draft.getValue("times").jsonArray.map { resolvedTime(it.jsonObject) },
        note = draft.string("note"),
        tags = draft.strings("tags"),
        mediaLocations = draft.getValue("mediaLocations").jsonArray.map(::point),
        deviceLocation = draft.getValue("deviceLocation").takeUnless(::isNull)?.jsonObject?.let { device ->
            DeviceLocation(requireNotNull(point(device.getValue("point"))), device.getValue("accuracyMeters").jsonPrimitive.doubleOrNull)
        },
        timeZone = draft.getValue("timeZone").takeUnless(::isNull)?.jsonPrimitive?.content,
        nowUtc = draft.string("nowUtc"),
    )

    private fun edit(edit: JsonObject): EntryEdit = EntryEdit(
        note = edit["note"]?.jsonPrimitive?.content,
        tags = edit["tags"]?.let { tags -> tags.jsonArray.map { it.jsonPrimitive.content } },
        time = edit["time"]?.jsonObject?.let { EntryTimeEdit(it.string("takenAtUtc"), it.long("utcOffsetMinutes")) },
        location = edit["location"]?.let { OptionalValue.Present(location(it)) } ?: OptionalValue.Absent,
        mediaOrder = edit["mediaOrder"]?.let { order -> order.jsonArray.map { it.jsonPrimitive.content } },
        addMedia = edit["addMedia"]?.jsonArray?.map(ContractSchemas.mediaDescriptorV1::decode),
        removeMediaId = edit["removeMediaId"]?.jsonPrimitive?.content,
        favorite = edit["favorite"]?.jsonPrimitive?.boolean,
        captions = edit["captions"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content },
    )
}
