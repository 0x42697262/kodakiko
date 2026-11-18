package dev.kodakiko.service.sync

import dev.kodakiko.contract.DiffPage
import dev.kodakiko.contract.EntryDto
import dev.kodakiko.contract.MediaDto
import dev.kodakiko.contract.ObjectDto
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.layoutFromCiphertextSize
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.SyncCalls
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.sync.LocalCollectionState
import dev.kodakiko.sync.LocalEntryState
import dev.kodakiko.sync.PULL_PAGE_LIMIT
import dev.kodakiko.sync.PulledEntryDecision
import dev.kodakiko.sync.RemoteCollectionState
import dev.kodakiko.sync.decidePulledEntry
import dev.kodakiko.sync.planCollections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole

data class PullResult(val pulledEntries: Int)

fun interface Pull {
    suspend fun pull(): PullResult
}

class PullDeps(
    val http: SyncHttp,
    val rowStore: RowStorePort,
    val files: EntryFiles,
    val onEntriesChanged: (List<String>) -> Unit,
    val log: SyncLog,
    val ioDispatcher: CoroutineDispatcher,
)

class ResyncLoopException : IllegalStateException(SyncText.RESYNC_LOOP)

private class PageChanges {
    val changed = LinkedHashSet<String>()
    val files = mutableListOf<String>()
    var pulled = 0
}

private fun plaintextSizeOf(sizeBytes: Long): Long =
    try {
        layoutFromCiphertextSize(sizeBytes, STREAM_CHUNK_SIZE_EXPONENT).plaintextSize
    } catch (_: Exception) {
        SyncServiceConstants.NO_PLAINTEXT_SIZE
    }

class DefaultPull(private val deps: PullDeps) : Pull {
    private val rowStore = deps.rowStore

    override suspend fun pull(): PullResult {
        val state = io { deps.http.request(SyncCalls.syncState()) }
        val local = io { rowStore.transaction { tx -> tx.collections.all() } }
        val remote = state.collections.map {
            RemoteCollectionState(it.id, it.kind, it.role, it.lastSeq, it.tombstonesPrunedThroughSeq, it.metaUpdatedAt)
        }
        val plan = planCollections(remote, local.map { LocalCollectionState(it.id, it.cursorSeq, it.metaUpdatedAt) })
        for (collectionId in plan.drop) dropCollection(collectionId)
        for (collectionId in plan.refetch) refetchCollection(collectionId)
        fun lastSeqOf(collectionId: String): Long = remote.firstOrNull { it.id == collectionId }?.lastSeq ?: SyncServiceConstants.INITIAL_CURSOR
        var pulledEntries = 0
        for (collectionId in plan.resync) pulledEntries += pullCollection(collectionId, true, lastSeqOf(collectionId))
        for (collectionId in plan.pull) pulledEntries += pullCollection(collectionId, false, lastSeqOf(collectionId))
        return PullResult(pulledEntries)
    }

    private suspend fun <T> io(work: () -> T): T = withContext(deps.ioDispatcher) { work() }

    private suspend fun finish(changed: Collection<String>, files: List<String>) {
        io { deleteFiles(deps.files, files) }
        if (changed.isNotEmpty()) deps.onEntriesChanged(changed.toList())
    }

    private suspend fun dropCollection(collectionId: String) {
        val dropped = io { dropLocalCollection(rowStore, collectionId) }
        io { deleteFiles(deps.files, dropped.files) }
        finish(dropped.changed, emptyList())
    }

    private suspend fun refetchCollection(collectionId: String) {
        val dto = io { deps.http.request(SyncCalls.collection(collectionId)) }
        val keyMaterial = decodeBytes(dto.keyMaterial)
        val metadataBlob = decodeBytes(dto.metadataBlob)
        io {
            rowStore.transaction { tx ->
                val existing = tx.collections.get(dto.id)
                tx.collections.put(
                    CollectionRow(
                        id = dto.id,
                        kind = dto.kind.toRow(),
                        ownerId = dto.ownerId,
                        ownerUsername = dto.ownerUsername,
                        role = dto.role.toRow(),
                        keyMaterial = keyMaterial,
                        metadataBlob = metadataBlob,
                        metaUpdatedAt = dto.metaUpdatedAt,
                        serverLastSeq = dto.lastSeq,
                        cursorSeq = existing?.cursorSeq ?: SyncServiceConstants.INITIAL_CURSOR,
                    ),
                )
            }
        }
        if (dto.kind == WireCollectionKind.HOME && dto.role == WireCollectionRole.OWNER) rememberHome(dto.id)
    }

    private suspend fun rememberHome(collectionId: String) {
        io {
            val stored = rowStore.kvGetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID)
            if (stored != null && (stored as? JsonPrimitive)?.isString != true) throw IllegalStateException(SyncText.INVALID_STORED_VALUE)
            if (stored == null) rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(collectionId))
        }
    }

    private suspend fun wipeMemberships(collectionId: String) {
        val changes = PageChanges()
        io {
            rowStore.transaction { tx ->
                val queuedEntryIds = tx.outbox.all().mapNotNull { entryIdOfPayload(it.payload) }.toSet()
                for (membership in tx.memberships.byCollection(collectionId)) {
                    val entry = tx.entries.get(membership.entryId)
                    if (entry?.localState == EntryLocalState.PENDING_CREATE) continue
                    tx.memberships.delete(membership.collectionId, membership.entryId)
                    val keep = entry == null ||
                        entry.localState != EntryLocalState.SYNCED ||
                        membership.entryId in queuedEntryIds ||
                        tx.memberships.byEntry(membership.entryId).isNotEmpty()
                    if (keep) continue
                    changes.files += removeEntryRows(tx, membership.entryId)
                    changes.changed += membership.entryId
                }
                tx.collections.get(collectionId)?.let { tx.collections.put(it.copy(cursorSeq = SyncServiceConstants.INITIAL_CURSOR)) }
            }
        }
        finish(changes.changed, changes.files)
    }

    private fun applyEntry(tx: RowStoreTx, local: EntryRow?, dto: EntryDto, blob: ByteArray): Boolean {
        val decision = decidePulledEntry(
            local?.let { LocalEntryState(it.localState.toPlan(), it.baseRevision ?: SyncServiceConstants.NO_REVISION) },
            dto.revision,
        )
        if (decision == PulledEntryDecision.REJECT_ROLLBACK) {
            deps.log.warn(
                SyncLogEvents.ROLLBACK_REJECTED,
                mapOf(
                    SyncLogFields.ENTRY_ID to dto.id,
                    SyncLogFields.PULLED_REVISION to dto.revision,
                    SyncLogFields.BASE_REVISION to (local?.baseRevision ?: SyncServiceConstants.NO_REVISION),
                ),
            )
            return false
        }
        val row = if (decision == PulledEntryDecision.UPDATE_TRASH_ONLY && local != null) {
            local.copy(trashedAt = dto.trashedAt, serverPendingBlob = blob, serverPendingRevision = dto.revision)
        } else {
            EntryRow(
                id = dto.id,
                ownerId = dto.ownerId,
                purpose = dto.purpose.toRow(),
                revision = dto.revision,
                metadataBlob = blob,
                baseMetadataBlob = blob,
                baseRevision = dto.revision,
                serverPendingBlob = null,
                serverPendingRevision = null,
                trashedAt = dto.trashedAt,
                createdAt = dto.createdAt,
                updatedAt = dto.updatedAt,
                localState = EntryLocalState.SYNCED,
            )
        }
        tx.entries.put(row)
        return true
    }

    private fun syncMedia(tx: RowStoreTx, entryId: String, serverMedia: List<MediaDto>, files: MutableList<String>) {
        val serverIds = serverMedia.map { it.id }.toSet()
        for (media in tx.media.byEntry(entryId)) {
            if (media.id in serverIds) continue
            if (tx.objects.byMedia(media.id).all { it.uploadState == ObjectUploadState.UPLOADED }) files += removeMediaRows(tx, media.id)
        }
        for (media in serverMedia) tx.media.put(MediaRow(media.id, media.entryId, decodeBytes(media.mediaKeyByEntry)))
    }

    private fun syncObjects(tx: RowStoreTx, objects: List<ObjectDto>, files: MutableList<String>) {
        for (dto in objects) {
            val existing = tx.objects.get(dto.id)
            tx.objects.put(
                ObjectRow(
                    id = dto.id,
                    mediaId = dto.mediaId,
                    variant = dto.variant.toRow(),
                    sizeBytes = dto.sizeBytes,
                    plaintextSize = plaintextSizeOf(dto.sizeBytes),
                    localState = existing?.localState ?: ObjectLocalState.MISSING,
                    uploadState = ObjectUploadState.UPLOADED,
                    uploadJobId = existing?.uploadJobId,
                    lastAccessAt = existing?.lastAccessAt ?: SyncServiceConstants.NEVER_ACCESSED,
                ),
            )
            for (older in tx.objects.byMedia(dto.mediaId)) {
                if (older.variant == dto.variant.toRow() && older.id != dto.id) {
                    tx.objects.delete(older.id)
                    files += older.id
                }
            }
        }
    }

    private fun applyMemberships(tx: RowStoreTx, collectionId: String, page: DiffPage, changes: PageChanges) {
        val keyWraps = page.memberships.associate { membership -> membership.entryId to membership.entryKeyByCollection?.let(::decodeBytes) }
        for (membership in page.memberships) {
            val wrap = keyWraps[membership.entryId]
            if (membership.removed || wrap == null) {
                tx.memberships.delete(collectionId, membership.entryId)
                changes.changed += membership.entryId
                if (tx.memberships.byEntry(membership.entryId).isEmpty()) changes.files += removeEntryRows(tx, membership.entryId)
            } else {
                tx.memberships.put(MembershipRow(collectionId, membership.entryId, wrap, membership.seq))
            }
        }
    }

    private suspend fun applyPage(collectionId: String, page: DiffPage, serverLastSeq: Long): Int {
        val blobs = page.entries.associate { it.id to decodeBytes(it.metadataBlob) }
        val mediaByEntry = page.media.groupBy { it.entryId }
        val objectsByEntry = page.objects.groupBy { item -> page.media.firstOrNull { it.id == item.mediaId }?.entryId.orEmpty() }
        val changes = PageChanges()
        io {
            rowStore.transaction { tx ->
                applyMemberships(tx, collectionId, page, changes)
                for (dto in page.entries) {
                    val blob = blobs[dto.id] ?: continue
                    if (!applyEntry(tx, tx.entries.get(dto.id), dto, blob)) continue
                    changes.pulled += 1
                    changes.changed += dto.id
                    syncMedia(tx, dto.id, mediaByEntry[dto.id].orEmpty(), changes.files)
                    syncObjects(tx, objectsByEntry[dto.id].orEmpty(), changes.files)
                }
                tx.collections.get(collectionId)?.let { tx.collections.put(it.copy(cursorSeq = page.nextCursor, serverLastSeq = serverLastSeq)) }
            }
        }
        finish(changes.changed, changes.files)
        return changes.pulled
    }

    private suspend fun pullCollection(collectionId: String, resync: Boolean, serverLastSeq: Long): Int {
        var cursor = SyncServiceConstants.INITIAL_CURSOR
        if (resync) {
            wipeMemberships(collectionId)
        } else {
            cursor = io { rowStore.transaction { tx -> tx.collections.get(collectionId) } }?.cursorSeq ?: SyncServiceConstants.INITIAL_CURSOR
        }
        var pulled = 0
        var restarts = 0
        while (true) {
            val page = io { deps.http.request(SyncCalls.diff(collectionId, cursor, PULL_PAGE_LIMIT.toLong())) }
            if (page.resyncRequired) {
                restarts += 1
                if (restarts > SyncServiceConstants.MAX_RESYNC_RESTARTS) throw ResyncLoopException()
                wipeMemberships(collectionId)
                cursor = SyncServiceConstants.INITIAL_CURSOR
                continue
            }
            pulled += applyPage(collectionId, page, serverLastSeq)
            cursor = page.nextCursor
            if (!page.hasMore) break
        }
        return pulled
    }
}
