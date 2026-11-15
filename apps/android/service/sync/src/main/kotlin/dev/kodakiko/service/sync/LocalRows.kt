package dev.kodakiko.service.sync

import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryFiles
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun stringField(payload: JsonElement, name: String): String? =
    ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun entryIdOfPayload(payload: JsonElement): String? = stringField(payload, SyncPayloadKeys.ENTRY_ID)

internal fun mediaIdOfPayload(payload: JsonElement): String? = stringField(payload, SyncPayloadKeys.MEDIA_ID)

internal fun outboxKindOf(row: OutboxRow): OutboxKind? = OutboxKind.entries.firstOrNull { it.wire == row.kind }

internal fun isUploadItem(row: OutboxRow): Boolean = row.kind == OutboxKind.CREATE_ENTRY.wire || row.kind == OutboxKind.ADD_MEDIA.wire

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

internal fun deleteFiles(files: EntryFiles, objectIds: List<String>) {
    objectIds.forEach(files::delete)
}

internal class DroppedCollection(val changed: List<String>, val files: List<String>)

internal fun dropLocalCollection(rowStore: RowStorePort, collectionId: String): DroppedCollection {
    val changed = LinkedHashSet<String>()
    val files = mutableListOf<String>()
    rowStore.transaction { tx ->
        for (membership in tx.memberships.byCollection(collectionId)) {
            tx.memberships.delete(membership.collectionId, membership.entryId)
            changed += membership.entryId
            if (tx.memberships.byEntry(membership.entryId).isEmpty()) files += removeEntryRows(tx, membership.entryId)
        }
        tx.collections.delete(collectionId)
    }
    return DroppedCollection(changed.toList(), files)
}
