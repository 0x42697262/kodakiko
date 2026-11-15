package dev.kodakiko.service.sync

import dev.kodakiko.contract.MediaCreateRequest
import dev.kodakiko.contract.OutboxPayloadSchemas
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.SyncCalls

internal suspend fun addMedia(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    val payload = OutboxPayloadSchemas.entryMedia.decode(item.payload)
    val media = deps.transaction { tx -> tx.media.get(payload.mediaId) } ?: return ExecutorResult.Done
    if (!mediaIsPosted(deps, payload.mediaId)) {
        deps.call(SyncCalls.addMedia(payload.entryId, MediaCreateRequest(payload.mediaId, encodeBytes(media.mediaKeyWrap))))
    }
    val uploaded = advanceUploads(deps, listOf(payload.mediaId))
    if (uploaded) deps.onEntriesChanged(listOf(payload.entryId))
    return if (uploaded) ExecutorResult.Done else ExecutorResult.Waiting
}

internal suspend fun removeMedia(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    val payload = OutboxPayloadSchemas.entryMedia.decode(item.payload)
    try {
        deps.call(SyncCalls.removeMedia(payload.mediaId))
    } catch (error: ApiError) {
        if (error.status != SyncServiceConstants.STATUS_NOT_FOUND) throw error
    }
    return ExecutorResult.Done
}

internal suspend fun trashEntry(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    deps.call(SyncCalls.trashEntry(OutboxPayloadSchemas.entry.decode(item.payload).entryId))
    return ExecutorResult.Done
}

internal suspend fun restoreEntry(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    deps.call(SyncCalls.restoreEntry(OutboxPayloadSchemas.entry.decode(item.payload).entryId))
    return ExecutorResult.Done
}

internal suspend fun deleteEntry(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    val entryId = OutboxPayloadSchemas.entry.decode(item.payload).entryId
    deps.call(SyncCalls.deleteEntry(entryId))
    val objectIds = deps.transaction { tx -> removeEntryRows(tx, entryId) }
    deps.deleteObjectFiles(objectIds)
    deps.onEntriesChanged(listOf(entryId))
    return ExecutorResult.Done
}
