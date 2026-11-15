package dev.kodakiko.service.entries

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.bytesEqual
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.NewOutboxRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.domain.EntryDraft
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.InvalidEditError
import dev.kodakiko.domain.addTag
import dev.kodakiko.domain.applyEntryEdit
import dev.kodakiko.domain.buildNewEntryMetadata
import dev.kodakiko.domain.currentTimeZone
import dev.kodakiko.domain.normalizeTag
import dev.kodakiko.domain.openEntryMetadata
import dev.kodakiko.domain.removeTag
import dev.kodakiko.service.account.AccountStorage
import dev.kodakiko.sync.metadataEquivalent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private enum class Mutation { CHANGED, UNCHANGED, SKIPPED }

private sealed interface Attempt<out T> {
    class Done<T>(val value: T) : Attempt<T>

    data object Stale : Attempt<Nothing>
}

private class Loaded(val row: EntryRow, val entryKey: ByteArray, val current: EntryMetadataV1)

private class MediaRows(val media: List<MediaRow>, val objects: List<ObjectRow>)

private class Removed(val objectIds: List<String>, val mediaIds: List<String>, val done: List<String>)

class DefaultEntriesService(private val deps: EntriesServiceDeps) : EntriesService {
    private val rowStore = deps.rowStore

    override suspend fun createFromTray(input: CreateEntryInput): String {
        val userId = requireUserId()
        val tray = deps.captureTray
        val generation = tray.generation
        val items = tray.items
        if (items.isEmpty()) throw EmptyTrayException()
        var taken: List<ImportedMedia> = emptyList()
        val entryId = try {
            insertEntry(userId, input, items) {
                taken = tray.takeIfUnchanged(generation, items) ?: throw TrayChangedException()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { restoreToTray(taken, generation) }
            throw failure
        }
        tray.clear(false)
        taken.forEach { wipe(it.mediaKey) }
        afterWrite(listOf(entryId))
        return entryId
    }

    override suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String {
        val userId = requireUserId()
        if (items.isEmpty()) throw EmptyTrayException()
        val entryId = insertEntry(userId, input, items) {}
        items.forEach { wipe(it.mediaKey) }
        afterWrite(listOf(entryId))
        return entryId
    }

    override suspend fun edit(entryId: String, edit: EntryEdit) {
        if (edit.addMedia != null || edit.removeMediaId != null) throw InvalidEditError(EntriesText.MEDIA_EDIT_NOT_ALLOWED)
        val userId = requireUserId()
        if (mutate(entryId, userId) { edit } == Mutation.CHANGED) afterWrite(listOf(entryId))
    }

    override suspend fun addMedia(entryId: String, items: List<ImportedMedia>) {
        val userId = requireUserId()
        loadOwnedEntry(entryId, userId)
        if (items.isEmpty()) throw InvalidEditError(EntriesText.NO_MEDIA_TO_ADD)
        val limit = deps.config()?.maxMediaPerEntry ?: EntriesConstants.FALLBACK_MAX_MEDIA_PER_ENTRY
        withRetries {
            val loaded = loadForEdit(entryId, userId)
            if (loaded.current.media.size + items.size > limit) throw InvalidEditError(EntriesText.tooManyMedia(limit))
            val now = deps.clock.nowIso()
            val next = applyEntryEdit(loaded.current, EntryEdit(addMedia = items.map { it.descriptor }), now)
            val blob = sealMetadata(loaded.entryKey, entryId, next)
            val rows = mediaRowsFor(entryId, loaded.entryKey, items, deps.clock.now())
            writeIfUnchanged(loaded) { tx, fresh ->
                tx.entries.put(metadataRowFor(fresh, blob, now))
                rows.media.forEach(tx.media::put)
                rows.objects.forEach(tx.objects::put)
                items.forEach { enqueue(tx, OutboxKind.ADD_MEDIA, entryMediaOutboxPayload(entryId, it.mediaId)) }
                enqueue(tx, OutboxKind.UPDATE_ENTRY_META, entryOutboxPayload(entryId))
            }
        }
        items.forEach { wipe(it.mediaKey) }
        afterWrite(listOf(entryId))
    }

    override suspend fun removeMedia(entryId: String, mediaId: String) {
        val userId = requireUserId()
        val objectIds = withRetries {
            val loaded = loadForEdit(entryId, userId)
            val now = deps.clock.nowIso()
            val next = applyEntryEdit(loaded.current, EntryEdit(removeMediaId = mediaId), now)
            val blob = sealMetadata(loaded.entryKey, entryId, next)
            writeIfUnchanged(loaded) { tx, fresh ->
                tx.entries.put(metadataRowFor(fresh, blob, now))
                enqueue(tx, OutboxKind.UPDATE_ENTRY_META, entryOutboxPayload(entryId))
                enqueue(tx, OutboxKind.REMOVE_MEDIA, entryMediaOutboxPayload(entryId, mediaId))
                removeMediaRows(tx, mediaId)
            }
        }
        deps.entryKeys.forget(entryId, mediaId)
        io {
            deps.files.deleteObjects(objectIds)
            deps.files.deletePlainThumbnail(mediaId)
        }
        afterWrite(listOf(entryId))
    }

    override suspend fun trash(entryIds: List<String>): BulkResult = setTrashed(entryIds, true)

    override suspend fun restore(entryIds: List<String>): BulkResult = setTrashed(entryIds, false)

    override suspend fun deletePermanently(entryIds: List<String>): BulkResult {
        val userId = requireUserId()
        val ids = entryIds.distinct()
        val removed = io {
            rowStore.transaction { tx ->
                val objectIds = mutableListOf<String>()
                val mediaIds = mutableListOf<String>()
                val done = mutableListOf<String>()
                for (entryId in ids) {
                    val row = tx.entries.get(entryId)
                    if (row == null || row.ownerId != userId) continue
                    val queueDelete = serverMayKnow(tx, row)
                    mediaIds += tx.media.byEntry(entryId).map { it.id }
                    objectIds += removeEntryRows(tx, entryId)
                    if (queueDelete) enqueue(tx, OutboxKind.DELETE_ENTRY, entryOutboxPayload(entryId))
                    done += entryId
                }
                Removed(objectIds, mediaIds, done)
            }
        }
        removed.done.forEach { deps.entryKeys.forget(it) }
        io {
            deps.files.deleteObjects(removed.objectIds)
            removed.mediaIds.forEach(deps.files::deletePlainThumbnail)
        }
        if (removed.done.isNotEmpty()) afterWrite(removed.done)
        return BulkResult(changed = removed.done.size, skipped = ids.size - removed.done.size)
    }

    override suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult =
        bulkTags(entryIds) { tags -> addTag(tags, tag).takeIf { it.rejected == null }?.tags }

    override suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult = bulkTags(entryIds) { tags -> withoutTag(tags, tag) }

    override suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult =
        bulkEdit(entryIds) { current -> if ((current.favorite == true) == favorite) null else EntryEdit(favorite = favorite) }

    override suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult =
        bulkTags(entryIds) { tags ->
            val old = normalizeTag(from)
            if (old == null || old !in tags) null else addTag(removeTag(tags, old), to).takeIf { it.rejected == null }?.tags
        }

    override suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult = bulkTags(entryIds) { tags -> withoutTag(tags, tag) }

    override suspend fun readMetadata(entryId: String): EntryMetadataV1 {
        val row = io { rowStore.transaction { tx -> tx.entries.get(entryId) } } ?: throw EntryNotFoundException()
        val entryKey = deps.entryKeys.entryKey(entryId)
        return openEntryMetadata(deps.sodium, entryKey, entryId, row.metadataBlob)
    }

    private suspend fun <T> io(work: () -> T): T = withContext(deps.ioDispatcher) { work() }

    private fun requireUserId(): String = deps.userId() ?: throw AccountLockedException()

    private suspend fun homeCollectionId(userId: String): String =
        io {
            AccountStorage.readString(rowStore, StorageKeys.ACCOUNT_HOME_COLLECTION_ID)
                ?: rowStore.transaction { tx -> tx.collections.all() }
                    .firstOrNull { it.kind == CollectionKind.HOME && it.role == CollectionRole.OWNER && it.ownerId == userId }
                    ?.id
                ?: throw HomeCollectionMissingException()
        }

    private suspend fun loadOwnedEntry(entryId: String, userId: String): EntryRow {
        val row = io { rowStore.transaction { tx -> tx.entries.get(entryId) } } ?: throw EntryNotFoundException()
        if (row.ownerId != userId) throw ReadOnlyEntryException()
        return row
    }

    private fun sealMetadata(entryKey: ByteArray, entryId: String, metadata: EntryMetadataV1): ByteArray =
        sealPayload(deps.sodium, entryKey, metadata, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId)

    private fun afterWrite(entryIds: List<String>) {
        deps.sync.notifyLocalWrite()
        deps.onEntriesChanged(entryIds)
    }

    private fun metadataRowFor(fresh: EntryRow, blob: ByteArray, now: String): EntryRow =
        fresh.copy(
            metadataBlob = blob,
            updatedAt = now,
            localState = if (fresh.localState == EntryLocalState.PENDING_CREATE) EntryLocalState.PENDING_CREATE else EntryLocalState.PENDING_UPDATE,
        )

    private fun enqueue(tx: RowStoreTx, kind: OutboxKind, payload: JsonObject) {
        tx.outbox.enqueue(
            NewOutboxRow(
                kind = kind.wire,
                payload = payload,
                state = OutboxState.QUEUED,
                createdAt = deps.clock.now(),
                attempts = EntriesConstants.NOTHING_ATTEMPTED,
                nextAttemptAt = 0,
                lastErrorCode = null,
            ),
        )
    }

    private fun enqueueUpdateOnce(tx: RowStoreTx, entryId: String) {
        val queued = tx.outbox.all().any {
            it.kind == OutboxKind.UPDATE_ENTRY_META.wire && it.state == OutboxState.QUEUED && entryIdOfPayload(it.payload) == entryId
        }
        if (!queued) enqueue(tx, OutboxKind.UPDATE_ENTRY_META, entryOutboxPayload(entryId))
    }

    private suspend fun loadForEdit(entryId: String, userId: String): Loaded {
        val row = loadOwnedEntry(entryId, userId)
        val entryKey = deps.entryKeys.entryKey(entryId)
        return Loaded(row, entryKey, openEntryMetadata(deps.sodium, entryKey, entryId, row.metadataBlob))
    }

    private suspend fun <T> writeIfUnchanged(loaded: Loaded, write: (RowStoreTx, EntryRow) -> T): Attempt<T> =
        io {
            rowStore.transaction { tx ->
                val fresh = tx.entries.get(loaded.row.id) ?: throw EntryNotFoundException()
                val unchanged = bytesEqual(fresh.metadataBlob, loaded.row.metadataBlob) && fresh.baseRevision == loaded.row.baseRevision
                if (unchanged) Attempt.Done(write(tx, fresh)) else Attempt.Stale
            }
        }

    private suspend fun <T> withRetries(attempt: suspend () -> Attempt<T>): T {
        repeat(EntriesConstants.EDIT_RETRY_ATTEMPTS) {
            val result = attempt()
            if (result is Attempt.Done) return result.value
        }
        throw EntryChangedException()
    }

    private suspend fun mutate(entryId: String, userId: String, build: (EntryMetadataV1) -> EntryEdit?): Mutation =
        withRetries {
            val loaded = loadForEdit(entryId, userId)
            val edit = build(loaded.current) ?: return@withRetries Attempt.Done(Mutation.SKIPPED)
            val now = deps.clock.nowIso()
            val next = applyEntryEdit(loaded.current, edit, now)
            if (metadataEquivalent(loaded.current, next)) return@withRetries Attempt.Done(Mutation.UNCHANGED)
            val blob = sealMetadata(loaded.entryKey, entryId, next)
            writeIfUnchanged(loaded) { tx, fresh ->
                tx.entries.put(metadataRowFor(fresh, blob, now))
                enqueueUpdateOnce(tx, entryId)
                Mutation.CHANGED
            }
        }

    private fun mediaRowsFor(entryId: String, entryKey: ByteArray, items: List<ImportedMedia>, now: Long): MediaRows {
        val media = items.map { item ->
            MediaRow(
                id = item.mediaId,
                entryId = entryId,
                mediaKeyWrap = wrapKey(deps.sodium, entryKey, item.mediaKey, AdContext.MEDIA_KEY_BY_ENTRY, bindingOf(entryId, item.mediaId)),
            )
        }
        val objects = items.flatMap { item ->
            listOf(
                objectRow(item.originalObjectId, item.mediaId, ObjectVariant.ORIGINAL, item.originalCiphertextBytes, item.descriptor.originalPlaintextBytes, now),
                objectRow(item.thumbnailObjectId, item.mediaId, ObjectVariant.THUMBNAIL, item.thumbnailCiphertextBytes, item.descriptor.thumbnail.plaintextBytes, now),
            )
        }
        return MediaRows(media, objects)
    }

    private fun objectRow(id: String, mediaId: String, variant: ObjectVariant, sizeBytes: Long, plaintextSize: Long, now: Long): ObjectRow =
        ObjectRow(
            id = id,
            mediaId = mediaId,
            variant = variant,
            sizeBytes = sizeBytes,
            plaintextSize = plaintextSize,
            localState = ObjectLocalState.PRESENT,
            uploadState = ObjectUploadState.LOCAL,
            uploadJobId = null,
            lastAccessAt = now,
        )

    private fun newEntryMetadata(input: CreateEntryInput, items: List<ImportedMedia>, now: String): EntryMetadataV1 {
        val built = buildNewEntryMetadata(
            EntryDraft(
                media = items.map { it.descriptor },
                times = items.map { it.time },
                note = input.note,
                tags = input.tags,
                mediaLocations = items.map { it.location?.point },
                deviceLocation = deps.captureTray.deviceLocation,
                timeZone = currentTimeZone(deps.clock.timeZone()),
                nowUtc = now,
            ),
        )
        val timed = input.time?.let { built.copy(takenAtUtc = it.takenAtUtc, utcOffsetMinutes = it.utcOffsetMinutes) } ?: built
        val located = when (val location = input.location) {
            OptionalValue.Absent -> timed
            is OptionalValue.Present -> timed.copy(location = location.value)
        }
        return ContractSchemas.entryMetadataV1.decode(ContractSchemas.entryMetadataV1.encode(located))
    }

    private suspend fun restoreToTray(items: List<ImportedMedia>, generation: Int) {
        for (item in items) deps.captureTray.addImported(item, generation)
    }

    private suspend fun insertEntry(userId: String, input: CreateEntryInput, items: List<ImportedMedia>, beforeWrite: () -> Unit): String {
        val homeId = homeCollectionId(userId)
        val collectionKey = deps.entryKeys.collectionKey(homeId)
        beforeWrite()
        val entryId = deps.randomId()
        val entryKey = deps.sodium.randomBytes(KEY_BYTES)
        try {
            val now = deps.clock.nowIso()
            val metadataBlob = sealMetadata(entryKey, entryId, newEntryMetadata(input, items, now))
            val entryKeyWrap = wrapKey(deps.sodium, collectionKey, entryKey, AdContext.ENTRY_KEY_BY_COLLECTION, bindingOf(homeId, entryId))
            val rows = mediaRowsFor(entryId, entryKey, items, deps.clock.now())
            io {
                rowStore.transaction { tx ->
                    tx.entries.put(newEntryRow(entryId, userId, metadataBlob, now))
                    tx.memberships.put(MembershipRow(homeId, entryId, entryKeyWrap, EntriesConstants.NOT_STARTED_SEQ))
                    rows.media.forEach(tx.media::put)
                    rows.objects.forEach(tx.objects::put)
                    enqueue(tx, OutboxKind.CREATE_ENTRY, entryOutboxPayload(entryId))
                }
            }
        } finally {
            wipe(entryKey)
        }
        return entryId
    }

    private fun newEntryRow(entryId: String, userId: String, metadataBlob: ByteArray, now: String): EntryRow =
        EntryRow(
            id = entryId,
            ownerId = userId,
            purpose = EntryPurpose.JOURNAL,
            revision = EntriesConstants.FIRST_REVISION,
            metadataBlob = metadataBlob,
            baseMetadataBlob = null,
            baseRevision = EntriesConstants.NEVER_POSTED_REVISION,
            serverPendingBlob = null,
            serverPendingRevision = null,
            trashedAt = null,
            createdAt = now,
            updatedAt = now,
            localState = EntryLocalState.PENDING_CREATE,
        )

    private suspend fun setTrashed(entryIds: List<String>, trashed: Boolean): BulkResult {
        val userId = requireUserId()
        val ids = entryIds.distinct()
        val now = deps.clock.nowIso()
        val sameKind = if (trashed) OutboxKind.TRASH_ENTRY else OutboxKind.RESTORE_ENTRY
        val oppositeKind = if (trashed) OutboxKind.RESTORE_ENTRY else OutboxKind.TRASH_ENTRY
        val changed = io {
            rowStore.transaction { tx ->
                val done = mutableListOf<String>()
                for (entryId in ids) {
                    val row = tx.entries.get(entryId)
                    if (row == null || row.ownerId != userId) continue
                    if ((row.trashedAt != null) == trashed) continue
                    tx.entries.put(row.copy(trashedAt = if (trashed) now else null))
                    val cancellable = if (deps.sync.isEntryInFlight(entryId)) {
                        null
                    } else {
                        tx.outbox.all().firstOrNull {
                            it.kind == oppositeKind.wire &&
                                it.state == OutboxState.QUEUED &&
                                it.attempts == EntriesConstants.NOTHING_ATTEMPTED &&
                                entryIdOfPayload(it.payload) == entryId
                        }
                    }
                    if (cancellable == null) enqueue(tx, sameKind, entryOutboxPayload(entryId)) else tx.outbox.delete(cancellable.id)
                    done += entryId
                }
                done
            }
        }
        if (changed.isNotEmpty()) afterWrite(changed)
        return BulkResult(changed = changed.size, skipped = ids.size - changed.size)
    }

    private fun serverMayKnow(tx: RowStoreTx, row: EntryRow): Boolean {
        if ((row.baseRevision ?: EntriesConstants.NEVER_POSTED_REVISION) >= EntriesConstants.FIRST_REVISION) return true
        if (deps.sync.isEntryInFlight(row.id)) return true
        return tx.outbox.all().any {
            it.kind == OutboxKind.CREATE_ENTRY.wire && it.attempts > EntriesConstants.NOTHING_ATTEMPTED && entryIdOfPayload(it.payload) == row.id
        }
    }

    private suspend fun bulkTags(entryIds: List<String>, build: (List<String>) -> List<String>?): BulkResult =
        bulkEdit(entryIds) { current -> build(current.tags)?.let { EntryEdit(tags = it) } }

    private suspend fun bulkEdit(entryIds: List<String>, build: (EntryMetadataV1) -> EntryEdit?): BulkResult {
        val userId = requireUserId()
        val changedIds = mutableListOf<String>()
        var skipped = 0
        for (entryId in entryIds.distinct()) {
            try {
                if (mutate(entryId, userId, build) == Mutation.CHANGED) changedIds += entryId else skipped += 1
            } catch (_: ReadOnlyEntryException) {
                skipped += 1
            } catch (_: EntryNotFoundException) {
                skipped += 1
            }
        }
        if (changedIds.isNotEmpty()) afterWrite(changedIds)
        return BulkResult(changed = changedIds.size, skipped = skipped)
    }
}

private fun withoutTag(tags: List<String>, tag: String): List<String>? {
    val normalized = normalizeTag(tag)
    return if (normalized == null || normalized !in tags) null else removeTag(tags, normalized)
}
