package dev.kodakiko.service.sync

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.EntryPatchRequest
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.OutboxPayloadSchemas
import dev.kodakiko.contract.RevisionConflictDetails
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.bytesEqual
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.SyncCalls
import dev.kodakiko.sync.PushFailure

private val CONFLICT_FAILURE: PushFailure = PushFailure.Http(SyncServiceConstants.STATUS_CONFLICT, ErrorCode.REVISION_CONFLICT, null)

private sealed interface Step {
    data class Finished(val result: ExecutorResult) : Step

    data object Rerun : Step

    data object Resolved : Step
}

private fun storedServerCopy(entry: EntryRow, baseRevision: Long): ServerEntryVersion? {
    val blob = entry.serverPendingBlob ?: return null
    val revision = entry.serverPendingRevision ?: return null
    return if (revision > baseRevision) ServerEntryVersion(revision, blob) else null
}

private suspend fun recordPosted(deps: ExecutorDeps, entryId: String, posted: ByteArray, revision: Long, updatedAt: String): Boolean =
    deps.transaction { tx ->
        val current = tx.entries.get(entryId) ?: return@transaction true
        val inSync = bytesEqual(current.metadataBlob, posted)
        tx.entries.put(
            current.copy(
                revision = revision,
                baseMetadataBlob = posted,
                baseRevision = revision,
                serverPendingBlob = null,
                serverPendingRevision = null,
                updatedAt = updatedAt,
                localState = if (inSync) EntryLocalState.SYNCED else EntryLocalState.PENDING_UPDATE,
            ),
        )
        inSync
    }

private suspend fun resolveConflict(deps: ExecutorDeps, entryId: String, server: ServerEntryVersion, conflicts: Int): Step {
    if (conflicts >= SyncServiceConstants.CONFLICT_INLINE_RETRIES) return Step.Finished(ExecutorResult.Failed(CONFLICT_FAILURE))
    val outcome = deps.conflict.resolve(entryId, server)
    return if (outcome == ConflictOutcome.ADOPTED) Step.Finished(ExecutorResult.Done) else Step.Resolved
}

private suspend fun markSynced(deps: ExecutorDeps, entryId: String) {
    deps.transaction { tx ->
        val current = tx.entries.get(entryId)
        if (current != null && current.localState != EntryLocalState.SYNCED) tx.entries.put(current.copy(localState = EntryLocalState.SYNCED))
    }
}

private fun conflictDetailsOf(error: ApiError): RevisionConflictDetails? {
    val details = error.details ?: return null
    return try {
        ContractSchemas.revisionConflictDetails.decode(details)
    } catch (_: ContractViolation) {
        null
    }
}

private suspend fun patch(deps: ExecutorDeps, entry: EntryRow, base: Long, conflicts: Int): Step {
    val posted = entry.metadataBlob
    val dto = try {
        deps.call(SyncCalls.patchEntry(entry.id, EntryPatchRequest(encodeBytes(posted), base)))
    } catch (error: ApiError) {
        if (error.code != ErrorCode.REVISION_CONFLICT) throw error
        val details = conflictDetailsOf(error) ?: throw error
        if (details.revision <= base) {
            deps.log.warn(
                SyncLogEvents.ROLLBACK_REJECTED,
                mapOf(
                    SyncLogFields.ENTRY_ID to entry.id,
                    SyncLogFields.PULLED_REVISION to details.revision,
                    SyncLogFields.BASE_REVISION to base,
                ),
            )
            return Step.Finished(ExecutorResult.Failed(CONFLICT_FAILURE))
        }
        return resolveConflict(deps, entry.id, ServerEntryVersion(details.revision, decodeBytes(details.metadataBlob)), conflicts)
    }
    val inSync = recordPosted(deps, entry.id, posted, dto.revision, dto.updatedAt)
    return if (inSync) Step.Finished(ExecutorResult.Done) else Step.Rerun
}

private suspend fun attempt(deps: ExecutorDeps, entryId: String, conflicts: Int): Step {
    val entry = deps.transaction { tx -> tx.entries.get(entryId) } ?: return Step.Finished(ExecutorResult.Done)
    val base = entry.baseRevision ?: SyncServiceConstants.NO_REVISION
    val baseBlob = entry.baseMetadataBlob
    if (base < SyncServiceConstants.CREATED_REVISION || baseBlob == null) return Step.Finished(ExecutorResult.Waiting)
    val stored = storedServerCopy(entry, base)
    if (stored != null) return resolveConflict(deps, entryId, stored, conflicts)
    if (bytesEqual(entry.metadataBlob, baseBlob)) {
        markSynced(deps, entryId)
        return Step.Finished(ExecutorResult.Done)
    }
    return patch(deps, entry, base, conflicts)
}

internal suspend fun updateEntryMeta(item: OutboxRow, deps: ExecutorDeps): ExecutorResult {
    val entryId = OutboxPayloadSchemas.entry.decode(item.payload).entryId
    var conflicts = 0
    repeat(SyncServiceConstants.UPDATE_RERUN_LIMIT) {
        when (val step = attempt(deps, entryId, conflicts)) {
            Step.Resolved -> conflicts += 1
            Step.Rerun -> Unit
            is Step.Finished -> return step.result
        }
    }
    return ExecutorResult.Waiting
}
