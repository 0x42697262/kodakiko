package dev.kodakiko.service.sync

import dev.kodakiko.contract.EntryCreateRequest
import dev.kodakiko.contract.EntryMediaRequest
import dev.kodakiko.contract.EntryMembershipRequest
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.OutboxPayloadSchemas
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.bytesEqual
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.SyncCalls

private class Snapshot(val entry: EntryRow?, val memberships: List<MembershipRow>, val media: List<MediaRow>)

private sealed interface PostOutcome {
    data object Posted : PostOutcome

    data class Stopped(val result: ExecutorResult) : PostOutcome
}

private suspend fun adoptServerCopy(deps: ExecutorDeps, entryId: String, blob: ByteArray, revision: Long) {
    deps.transaction { tx ->
        val current = tx.entries.get(entryId) ?: return@transaction
        tx.entries.put(
            current.copy(
                baseMetadataBlob = blob,
                baseRevision = revision,
                revision = revision,
                serverPendingBlob = null,
                serverPendingRevision = null,
            ),
        )
    }
}

private suspend fun adoptExistingEntry(deps: ExecutorDeps, entry: EntryRow, error: ApiError): PostOutcome {
    val existing = deps.call(SyncCalls.getEntry(entry.id))
    if (existing.ownerId != entry.ownerId) return PostOutcome.Stopped(ExecutorResult.Failed(httpFailureOf(error)))
    adoptServerCopy(deps, entry.id, decodeBytes(existing.metadataBlob), existing.revision)
    return PostOutcome.Posted
}

private fun createRequestOf(entry: EntryRow, snapshot: Snapshot): EntryCreateRequest =
    EntryCreateRequest(
        id = entry.id,
        purpose = entry.purpose.toWire(),
        metadataBlob = encodeBytes(entry.metadataBlob),
        memberships = snapshot.memberships.map { EntryMembershipRequest(it.collectionId, encodeBytes(it.entryKeyWrap)) },
        media = snapshot.media.map { EntryMediaRequest(it.id, encodeBytes(it.mediaKeyWrap)) },
    )

private suspend fun postEntry(deps: ExecutorDeps, entry: EntryRow, snapshot: Snapshot): PostOutcome {
    val posted = entry.metadataBlob
    try {
        deps.call(SyncCalls.createEntry(createRequestOf(entry, snapshot)))
    } catch (error: ApiError) {
        if (error.code != ErrorCode.ID_CONFLICT) throw error
        return adoptExistingEntry(deps, entry, error)
    }
    adoptServerCopy(deps, entry.id, posted, SyncServiceConstants.CREATED_REVISION)
    return PostOutcome.Posted
}

internal suspend fun createEntry(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    val entryId = OutboxPayloadSchemas.entry.decode(item.payload).entryId
    val snapshot = deps.transaction { tx -> Snapshot(tx.entries.get(entryId), tx.memberships.byEntry(entryId), tx.media.byEntry(entryId)) }
    val entry = snapshot.entry ?: return ExecutorResult.Done
    if ((entry.baseRevision ?: SyncServiceConstants.NO_REVISION) < SyncServiceConstants.CREATED_REVISION) {
        val pendingBlob = entry.serverPendingBlob
        val pendingRevision = entry.serverPendingRevision ?: SyncServiceConstants.NO_REVISION
        if (pendingBlob != null && pendingRevision >= SyncServiceConstants.CREATED_REVISION) {
            adoptServerCopy(deps, entryId, pendingBlob, pendingRevision)
        } else {
            val outcome = postEntry(deps, entry, snapshot)
            if (outcome is PostOutcome.Stopped) return outcome.result
        }
    }
    val uploaded = advanceUploads(deps, snapshot.media.map { it.id })
    if (!uploaded) return ExecutorResult.Waiting
    deps.transaction { tx ->
        val current = tx.entries.get(entryId) ?: return@transaction
        val base = current.baseMetadataBlob
        val unchanged = base != null && bytesEqual(current.metadataBlob, base)
        tx.entries.put(current.copy(localState = if (unchanged) EntryLocalState.SYNCED else EntryLocalState.PENDING_UPDATE))
    }
    deps.onEntriesChanged(listOf(entryId))
    return ExecutorResult.Done
}
