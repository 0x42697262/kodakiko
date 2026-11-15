package dev.kodakiko.service.sync

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.bytesEqual
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.domain.openEntryMetadata
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.sync.mergeMetadata
import dev.kodakiko.sync.metadataEquivalent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class ServerEntryVersion(val revision: Long, val metadataBlob: ByteArray)

enum class ConflictOutcome {
    ADOPTED,
    RETRY,
}

fun interface ConflictResolver {
    suspend fun resolve(entryId: String, server: ServerEntryVersion): ConflictOutcome
}

class ConflictResolverDeps(
    val sodium: Sodium,
    val rowStore: RowStorePort,
    val entryKeys: EntryKeys,
    val clock: SyncClock,
    val ioDispatcher: CoroutineDispatcher,
)

private sealed interface ResolveAttempt {
    data class Resolved(val outcome: ConflictOutcome) : ResolveAttempt

    data object Changed : ResolveAttempt
}

class DefaultConflictResolver(private val deps: ConflictResolverDeps) : ConflictResolver {
    override suspend fun resolve(entryId: String, server: ServerEntryVersion): ConflictOutcome {
        repeat(SyncServiceConstants.CONFLICT_RESOLVE_ATTEMPTS) {
            val attempt = attempt(entryId, server)
            if (attempt is ResolveAttempt.Resolved) return attempt.outcome
        }
        return ConflictOutcome.RETRY
    }

    private suspend fun attempt(entryId: String, server: ServerEntryVersion): ResolveAttempt {
        val sodium = deps.sodium
        val entry = withContext(deps.ioDispatcher) { deps.rowStore.transaction { tx -> tx.entries.get(entryId) } }
            ?: return ResolveAttempt.Resolved(ConflictOutcome.ADOPTED)
        val entryKey = deps.entryKeys.entryKey(entryId)
        val serverMetadata = openEntryMetadata(sodium, entryKey, entryId, server.metadataBlob)
        val localMetadata = openEntryMetadata(sodium, entryKey, entryId, entry.metadataBlob)
        val ancestor = entry.baseMetadataBlob?.let { openEntryMetadata(sodium, entryKey, entryId, it) } ?: serverMetadata
        val merged = mergeMetadata(ancestor, localMetadata, serverMetadata, deps.clock.nowIso())
        val adopt = metadataEquivalent(merged, serverMetadata)
        val mergedBlob = if (adopt) {
            server.metadataBlob
        } else {
            sealPayload(sodium, entryKey, merged, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId)
        }
        val stored = withContext(deps.ioDispatcher) {
            deps.rowStore.transaction { tx ->
                val current = tx.entries.get(entryId) ?: return@transaction true
                if (!bytesEqual(current.metadataBlob, entry.metadataBlob) || current.baseRevision != entry.baseRevision) return@transaction false
                tx.entries.put(
                    current.copy(
                        revision = server.revision,
                        metadataBlob = mergedBlob,
                        baseMetadataBlob = server.metadataBlob,
                        baseRevision = server.revision,
                        serverPendingBlob = null,
                        serverPendingRevision = null,
                        localState = if (adopt) EntryLocalState.SYNCED else EntryLocalState.PENDING_UPDATE,
                    ),
                )
                true
            }
        }
        if (!stored) return ResolveAttempt.Changed
        return ResolveAttempt.Resolved(if (adopt) ConflictOutcome.ADOPTED else ConflictOutcome.RETRY)
    }
}
