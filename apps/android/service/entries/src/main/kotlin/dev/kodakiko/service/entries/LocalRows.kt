package dev.kodakiko.service.entries

import dev.kodakiko.data.db.RowStoreTx
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun entryIdOfPayload(payload: JsonElement): String? =
    ((payload as? JsonObject)?.get(OutboxPayloadKeys.ENTRY_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun entryOutboxPayload(entryId: String): JsonObject = buildJsonObject { put(OutboxPayloadKeys.ENTRY_ID, entryId) }

internal fun entryMediaOutboxPayload(entryId: String, mediaId: String): JsonObject =
    buildJsonObject {
        put(OutboxPayloadKeys.ENTRY_ID, entryId)
        put(OutboxPayloadKeys.MEDIA_ID, mediaId)
    }

internal fun removeMediaRows(tx: RowStoreTx, mediaId: String): List<String> {
    val objects = tx.objects.byMedia(mediaId)
    objects.forEach { tx.objects.delete(it.id) }
    tx.media.delete(mediaId)
    return objects.map { it.id }
}

internal fun removeEntryRows(tx: RowStoreTx, entryId: String): List<String> {
    val objectIds = tx.media.byEntry(entryId).flatMap { removeMediaRows(tx, it.id) }
    tx.memberships.byEntry(entryId).forEach { tx.memberships.delete(it.collectionId, it.entryId) }
    tx.pins.delete(entryId)
    tx.entries.delete(entryId)
    tx.outbox.all().filter { entryIdOfPayload(it.payload) == entryId }.forEach { tx.outbox.delete(it.id) }
    return objectIds
}

internal fun EntryFiles.deleteObjects(objectIds: List<String>) {
    objectIds.forEach(::delete)
}
