package dev.kodakiko.sync

import dev.kodakiko.contract.CollectionKind
import dev.kodakiko.contract.CollectionRole
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ENTRY_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.contract.WireEnum
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

data class SyncReplay(val result: JsonElement, val randomCalls: Int)

object SyncGoldenAdapter : GoldenAdapter {
    override fun call(fn: String, args: JsonObject): JsonElement = replay(fn, args).result

    fun replay(fn: String, args: JsonObject): SyncReplay {
        var randomCalls = 0
        val random: () -> Double = { args.getValue("randomValues").jsonArray[randomCalls++].jsonPrimitive.double }
        val result = when (fn) {
            "nextAttemptAt" -> number(nextAttemptAt(args.int("attempts"), args.double("now"), random))
            "classifyPushFailure" -> decision(
                classifyPushFailure(
                    wire(OutboxKind.entries, args.string("kind")),
                    failure(args.getValue("failure").jsonObject),
                    args.int("attempts"),
                    args.double("now"),
                    random,
                ),
            )
            "planPushBatch" -> JsonArray(
                planPushBatch(
                    args.getValue("items").jsonArray.map { item(it.jsonObject) },
                    args.strings("inFlightEntryIds").toSet(),
                    args.double("now"),
                    args.int("capacity"),
                ).map(::JsonPrimitive),
            )
            "decidePulledEntry" -> JsonPrimitive(decidePulledEntry(localEntry(args.getValue("local")), args.long("pulledRevision")).wire)
            "planCollections" -> collectionsPlan(
                planCollections(
                    args.getValue("remote").jsonArray.map { remoteCollection(it.jsonObject) },
                    args.getValue("local").jsonArray.map { localCollection(it.jsonObject) },
                ),
            )
            "mergeMetadata" -> ContractSchemas.entryMetadataV1.encode(
                mergeMetadata(metadata(args, "ancestor"), metadata(args, "local"), metadata(args, "server"), args.string("nowUtc")),
            )
            "metadataEquivalent" -> JsonPrimitive(metadataEquivalent(metadata(args, "left"), metadata(args, "right")))
            else -> throw UnsupportedGoldenFunction(fn)
        }
        return SyncReplay(result, randomCalls)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

    private fun JsonObject.nullableString(key: String): String? = getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun <E : WireEnum> wire(entries: List<E>, value: String): E = entries.first { it.wire == value }

    private fun strings(values: List<String>): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun number(value: Double): JsonElement = when {
        value.isNaN() -> JsonObject(mapOf("\$number" to JsonPrimitive("NaN")))
        value.isInfinite() -> JsonObject(mapOf("\$number" to JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")))
        else -> JsonPrimitive(value)
    }

    private fun failure(json: JsonObject): PushFailure = when (val kind = json.string("kind")) {
        "network" -> PushFailure.Network
        "http" -> PushFailure.Http(
            status = json.int("status"),
            code = wire(ErrorCode.entries, json.string("code")),
            retryAfterSeconds = json.getValue("retryAfterSeconds").jsonPrimitive.doubleOrNull,
        )
        else -> error("unknown failure kind $kind")
    }

    private fun decision(decision: FailureDecision): JsonElement = when (decision) {
        is FailureDecision.Retry -> JsonObject(mapOf("action" to JsonPrimitive("retry"), "at" to number(decision.at)))
        FailureDecision.Conflict -> action("conflict")
        FailureDecision.Drop -> action("drop")
        FailureDecision.Dead -> action("dead")
        FailureDecision.SignedOut -> action("signedOut")
    }

    private fun action(name: String): JsonElement = JsonObject(mapOf("action" to JsonPrimitive(name)))

    private fun item(json: JsonObject): OutboxItemView = OutboxItemView(
        id = json.long("id"),
        kind = wire(OutboxKind.entries, json.string("kind")),
        entryId = json.nullableString("entryId"),
        state = wire(OutboxItemState.entries, json.string("state")),
        nextAttemptAt = json.double("nextAttemptAt"),
    )

    private fun localEntry(json: JsonElement): LocalEntryState? =
        (json as? JsonObject)?.let { LocalEntryState(wire(EntryLocalState.entries, it.string("localState")), it.long("baseRevision")) }

    private fun remoteCollection(json: JsonObject): RemoteCollectionState = RemoteCollectionState(
        id = json.string("id"),
        kind = wire(CollectionKind.entries, json.string("kind")),
        role = wire(CollectionRole.entries, json.string("role")),
        lastSeq = json.long("lastSeq"),
        tombstonesPrunedThroughSeq = json.long("tombstonesPrunedThroughSeq"),
        metaUpdatedAt = json.string("metaUpdatedAt"),
    )

    private fun localCollection(json: JsonObject): LocalCollectionState =
        LocalCollectionState(json.string("id"), json.long("cursorSeq"), json.string("metaUpdatedAt"))

    private fun collectionsPlan(plan: CollectionsPlan): JsonElement = JsonObject(
        mapOf(
            "refetch" to strings(plan.refetch),
            "pull" to strings(plan.pull),
            "resync" to strings(plan.resync),
            "drop" to strings(plan.drop),
        ),
    )

    private fun location(json: JsonElement): EntryLocation? = (json as? JsonObject)?.let {
        EntryLocation(
            latitude = it.double("latitude"),
            longitude = it.double("longitude"),
            accuracyMeters = it.getValue("accuracyMeters").jsonPrimitive.doubleOrNull,
            source = wire(LocationSource.entries, it.string("source")),
        )
    }

    private fun metadata(args: JsonObject, key: String): EntryMetadataV1 {
        val json = args.getValue(key).jsonObject
        check(json.int("schemaVersion") == ENTRY_METADATA_SCHEMA_VERSION) { "$key has an unsupported schemaVersion" }
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
}
