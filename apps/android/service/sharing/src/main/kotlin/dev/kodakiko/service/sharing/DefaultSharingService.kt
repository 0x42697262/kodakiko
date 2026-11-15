package dev.kodakiko.service.sharing

import dev.kodakiko.account.normalizeUsername
import dev.kodakiko.contract.CollectionCreateRequest
import dev.kodakiko.contract.CollectionMetadataV1
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.buildSharePayload
import dev.kodakiko.crypto.publicKeyFingerprint
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.net.SharingCalls
import dev.kodakiko.service.sync.deleteFiles
import dev.kodakiko.service.sync.dropLocalCollection
import kotlinx.coroutines.withContext
import dev.kodakiko.contract.CollectionKind as WireCollectionKind

private enum class AddOutcome {
    ADDED,
    ALREADY_IN,
    NOT_SYNCED,
    FAILED,
}

private fun isStatus(error: Throwable, status: Int): Boolean = error is ApiError && error.status == status

class DefaultSharingService(private val deps: SharingServiceDeps) : SharingService {
    private val sodium = deps.sodium
    private val rowStore = deps.rowStore

    override suspend fun createJournal(name: String): String {
        val account = requireAccount()
        val id = deps.randomId()
        val collectionKey = sodium.randomBytes(KEY_BYTES)
        try {
            val keyByOwner = wrapKey(sodium, account.keys.masterKey, collectionKey, AdContext.COLLECTION_KEY_BY_MASTER, id)
            val metadataBlob = sealName(collectionKey, id, name, deps.clock.nowIso())
            val dto = online(
                SharingCalls.create(CollectionCreateRequest(id, WireCollectionKind.SHARED, encodeBytes(keyByOwner), encodeBytes(metadataBlob))),
            )
            io {
                rowStore.transaction { tx ->
                    tx.collections.put(
                        CollectionRow(
                            id = id,
                            kind = CollectionKind.SHARED,
                            ownerId = dto.ownerId,
                            ownerUsername = dto.ownerUsername,
                            role = CollectionRole.OWNER,
                            keyMaterial = keyByOwner,
                            metadataBlob = metadataBlob,
                            metaUpdatedAt = dto.metaUpdatedAt,
                            serverLastSeq = dto.lastSeq,
                            cursorSeq = SharingConstants.INITIAL_CURSOR,
                        ),
                    )
                }
            }
        } finally {
            wipe(collectionKey)
        }
        deps.afterChange(emptyList())
        return id
    }

    override suspend fun renameJournal(id: String, name: String) {
        requireAccount()
        ownedRow(id)
        val key = deps.entryKeys.collectionKey(id)
        val metadataBlob = sealName(key, id, name, deps.clock.nowIso())
        val dto = online(SharingCalls.rename(id, encodeBytes(metadataBlob)))
        io {
            rowStore.transaction { tx ->
                tx.collections.get(id)?.let { row -> tx.collections.put(row.copy(metadataBlob = metadataBlob, metaUpdatedAt = dto.metaUpdatedAt)) }
            }
        }
        deps.afterChange(emptyList())
    }

    override suspend fun deleteJournal(id: String) {
        requireAccount()
        ownedRow(id)
        online(SharingCalls.remove(id))
        val touched = io {
            rowStore.transaction { tx ->
                val memberships = tx.memberships.byCollection(id)
                memberships.forEach { tx.memberships.delete(id, it.entryId) }
                tx.collections.delete(id)
                memberships.map { it.entryId }
            }
        }
        deps.afterChange(touched)
    }

    override suspend fun addEntries(collectionId: String, entryIds: List<String>): AddEntriesResult {
        val account = requireAccount()
        ownedRow(collectionId)
        val collectionKey = deps.entryKeys.collectionKey(collectionId)
        val outcomes = AddOutcome.entries.associateWith { mutableListOf<String>() }
        for (entryId in entryIds) {
            outcomes.getValue(addOne(collectionId, collectionKey, entryId, account.userId)) += entryId
        }
        val result = AddEntriesResult(
            added = outcomes.getValue(AddOutcome.ADDED),
            alreadyIn = outcomes.getValue(AddOutcome.ALREADY_IN),
            notSynced = outcomes.getValue(AddOutcome.NOT_SYNCED),
            failed = outcomes.getValue(AddOutcome.FAILED),
        )
        if (result.added.isNotEmpty()) deps.afterChange(result.added)
        return result
    }

    override suspend fun removeEntries(collectionId: String, entryIds: List<String>) {
        requireAccount()
        ownedRow(collectionId)
        for (entryId in entryIds) {
            online(SharingCalls.removeEntry(collectionId, entryId))
            io { rowStore.transaction { tx -> tx.memberships.delete(collectionId, entryId) } }
        }
        deps.afterChange(entryIds)
    }

    override suspend fun lookupRecipient(username: String): Recipient {
        requireAccount()
        try {
            val found = online(SharingCalls.publicKey(normalizeUsername(username)))
            val publicKey = decodeBytes(found.publicKey)
            return Recipient(found.userId, found.username, publicKey, publicKeyFingerprint(sodium, publicKey))
        } catch (error: ApiError) {
            if (isStatus(error, SharingConstants.STATUS_NOT_FOUND)) throw SharingException(SharingErrorReason.USER_NOT_FOUND)
            throw error
        }
    }

    override suspend fun share(collectionId: String, recipient: Recipient) {
        requireAccount()
        ownedRow(collectionId)
        val collectionKey = deps.entryKeys.collectionKey(collectionId)
        val payload = buildSharePayload(collectionId, collectionKey)
        val sealed = try {
            sodium.boxSeal(payload, recipient.publicKey)
        } finally {
            wipe(payload)
        }
        try {
            online(SharingCalls.share(collectionId, recipient.userId, encodeBytes(sealed)))
        } catch (error: ApiError) {
            if (isStatus(error, SharingConstants.STATUS_CONFLICT)) throw SharingException(SharingErrorReason.ALREADY_SHARED)
            throw error
        }
        deps.afterChange(emptyList())
    }

    override suspend fun listShares(collectionId: String): List<ShareDto> {
        requireAccount()
        return online(SharingCalls.listShares(collectionId)).items
    }

    override suspend fun unshare(collectionId: String, recipientUserId: String) {
        requireAccount()
        online(SharingCalls.unshare(collectionId, recipientUserId))
        deps.afterChange(emptyList())
    }

    override suspend fun leave(collectionId: String) {
        val account = requireAccount()
        online(SharingCalls.unshare(collectionId, account.userId))
        val dropped = io { dropLocalCollection(rowStore, collectionId) }
        io { deleteFiles(deps.files, dropped.files) }
        deps.afterChange(dropped.changed)
    }

    override fun myFingerprint(): String {
        val account = deps.account() ?: return ""
        return publicKeyFingerprint(sodium, account.keys.publicKey)
    }

    private fun requireAccount(): SharingAccount {
        if (!deps.sharingEnabled()) throw SharingException(SharingErrorReason.DISABLED)
        return deps.account() ?: throw SharingException(SharingErrorReason.LOCKED)
    }

    private suspend fun <T> io(work: () -> T): T = withContext(deps.ioDispatcher) { work() }

    private suspend fun <T> online(call: ApiCall<T>): T =
        try {
            io { deps.http.request(call) }
        } catch (_: NetworkError) {
            throw SharingException(SharingErrorReason.OFFLINE)
        }

    private fun sealName(key: ByteArray, id: String, name: String, createdAtUtc: String): ByteArray =
        sealPayload(sodium, key, CollectionMetadataV1(name, createdAtUtc), ContractSchemas.collectionMetadataV1, AdContext.COLLECTION_METADATA, id)

    private suspend fun ownedRow(id: String): CollectionRow {
        val row = io { rowStore.transaction { tx -> tx.collections.get(id) } }
        if (row == null || row.role != CollectionRole.OWNER) throw SharingException(SharingErrorReason.NOT_OWNER)
        return row
    }

    private suspend fun addOne(collectionId: String, collectionKey: ByteArray, entryId: String, userId: String): AddOutcome {
        val (entry, membership) = io {
            rowStore.transaction { tx -> tx.entries.get(entryId) to tx.memberships.byEntry(entryId).firstOrNull { it.collectionId == collectionId } }
        }
        if (entry == null || entry.ownerId != userId) return AddOutcome.FAILED
        if (membership != null) return AddOutcome.ALREADY_IN
        if ((entry.baseRevision ?: SharingConstants.NO_REVISION) == SharingConstants.NO_REVISION) return AddOutcome.NOT_SYNCED
        val entryKey = deps.entryKeys.entryKey(entryId)
        val wrap = wrapKey(sodium, collectionKey, entryKey, AdContext.ENTRY_KEY_BY_COLLECTION, collectionId + SharingConstants.KEY_BINDING_SEPARATOR + entryId)
        try {
            online(SharingCalls.addEntry(collectionId, entryId, encodeBytes(wrap)))
        } catch (error: ApiError) {
            if (isStatus(error, SharingConstants.STATUS_CONFLICT)) return AddOutcome.ALREADY_IN
            if (isStatus(error, SharingConstants.STATUS_NOT_FOUND)) return AddOutcome.FAILED
            throw error
        }
        io { rowStore.transaction { tx -> tx.memberships.put(MembershipRow(collectionId, entryId, wrap, SharingConstants.NEW_MEMBERSHIP_SEQ)) } }
        return AddOutcome.ADDED
    }
}
