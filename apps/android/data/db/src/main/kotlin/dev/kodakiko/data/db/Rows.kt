package dev.kodakiko.data.db

import kotlinx.serialization.json.JsonElement

data class CollectionRow(
    val id: String,
    val kind: CollectionKind,
    val ownerId: String,
    val ownerUsername: String,
    val role: CollectionRole,
    val keyMaterial: ByteArray,
    val metadataBlob: ByteArray,
    val metaUpdatedAt: String,
    val serverLastSeq: Long,
    val cursorSeq: Long,
)

data class EntryRow(
    val id: String,
    val ownerId: String,
    val purpose: EntryPurpose,
    val revision: Long,
    val metadataBlob: ByteArray,
    val baseMetadataBlob: ByteArray?,
    val baseRevision: Long?,
    val serverPendingBlob: ByteArray?,
    val serverPendingRevision: Long?,
    val trashedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val localState: EntryLocalState,
)

data class MembershipRow(
    val collectionId: String,
    val entryId: String,
    val entryKeyWrap: ByteArray,
    val seq: Long,
)

data class MediaRow(
    val id: String,
    val entryId: String,
    val mediaKeyWrap: ByteArray,
)

data class ObjectRow(
    val id: String,
    val mediaId: String,
    val variant: ObjectVariant,
    val sizeBytes: Long,
    val plaintextSize: Long,
    val localState: ObjectLocalState,
    val uploadState: ObjectUploadState,
    val uploadJobId: String?,
    val lastAccessAt: Long,
)

data class OutboxRow(
    val id: Long,
    val kind: String,
    val payload: JsonElement,
    val state: OutboxState,
    val createdAt: Long,
    val attempts: Long,
    val nextAttemptAt: Long,
    val lastErrorCode: String?,
)

data class NewOutboxRow(
    val kind: String,
    val payload: JsonElement,
    val state: OutboxState,
    val createdAt: Long,
    val attempts: Long,
    val nextAttemptAt: Long,
    val lastErrorCode: String?,
)

data class PinRow(
    val entryId: String,
    val pinnedAt: Long,
)
