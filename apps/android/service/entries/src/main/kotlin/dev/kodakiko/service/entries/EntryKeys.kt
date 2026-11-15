package dev.kodakiko.service.entries

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.openSharePayload
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface EntryKeys {
    suspend fun collectionKey(collectionId: String): ByteArray

    suspend fun entryKey(entryId: String): ByteArray

    suspend fun mediaKey(entryId: String, mediaId: String): ByteArray

    suspend fun variantKey(entryId: String, mediaId: String, variant: ObjectVariant): ByteArray

    fun forget(entryId: String, mediaId: String? = null)

    fun clear()
}

class EntryKeysDeps(
    val sodium: Sodium,
    val rowStore: RowStorePort,
    val account: () -> AccountKeys?,
    val ioDispatcher: CoroutineDispatcher,
)

class EntryKeyCache(private val deps: EntryKeysDeps) : EntryKeys {
    private val lock = Any()
    private val collectionKeys = HashMap<String, ByteArray>()
    private val entryKeys = HashMap<String, ByteArray>()
    private val mediaKeys = HashMap<String, ByteArray>()

    @Volatile
    private var generation = 0

    override suspend fun collectionKey(collectionId: String): ByteArray {
        val account = requireAccount()
        val started = generation
        cached(collectionKeys, collectionId)?.let { return it }
        val collection = withContext(deps.ioDispatcher) { deps.rowStore.transaction { tx -> tx.collections.get(collectionId) } }
        requireGeneration(started, null)
        if (collection == null) throw EntryKeyUnavailableException()
        return remember(collectionKeys, collectionId, started, resolveCollectionKey(account, collection))
    }

    override suspend fun entryKey(entryId: String): ByteArray {
        requireAccount()
        val started = generation
        cached(entryKeys, entryId)?.let { return it }
        val ordered = withContext(deps.ioDispatcher) { deps.rowStore.transaction { tx -> homeFirst(tx.memberships.byEntry(entryId)) { tx.collections.get(it)?.kind } } }
        requireGeneration(started, null)
        for (membership in ordered) {
            try {
                return unwrapEntryKey(membership, entryId, started)
            } catch (failure: AccountLockedException) {
                throw failure
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                continue
            }
        }
        throw EntryKeyUnavailableException()
    }

    override suspend fun mediaKey(entryId: String, mediaId: String): ByteArray {
        requireAccount()
        val started = generation
        val cacheKey = bindingOf(entryId, mediaId)
        cached(mediaKeys, cacheKey)?.let { return it }
        val parent = entryKey(entryId)
        val row = withContext(deps.ioDispatcher) { deps.rowStore.transaction { tx -> tx.media.get(mediaId) } }
        requireGeneration(started, null)
        if (row == null || row.entryId != entryId) throw EntryKeyUnavailableException()
        val key = unwrapKey(deps.sodium, parent, row.mediaKeyWrap, AdContext.MEDIA_KEY_BY_ENTRY, cacheKey)
        return remember(mediaKeys, cacheKey, started, key)
    }

    override suspend fun variantKey(entryId: String, mediaId: String, variant: ObjectVariant): ByteArray {
        val parent = mediaKey(entryId, mediaId)
        return deps.sodium.kdfDerive(KEY_BYTES, subkeyIdOf(variant), KdfContext.MEDIA.value, parent)
    }

    override fun forget(entryId: String, mediaId: String?) {
        synchronized(lock) {
            if (mediaId != null) {
                dropMediaKey(bindingOf(entryId, mediaId))
                return
            }
            val prefix = entryId + EntriesConstants.KEY_BINDING_SEPARATOR
            mediaKeys.keys.filter { it.startsWith(prefix) }.forEach(::dropMediaKey)
            entryKeys.remove(entryId)?.let(::wipe)
        }
    }

    override fun clear() {
        synchronized(lock) {
            generation += 1
            for (cache in listOf(collectionKeys, entryKeys, mediaKeys)) {
                cache.values.forEach(::wipe)
                cache.clear()
            }
        }
    }

    private suspend fun unwrapEntryKey(membership: MembershipRow, entryId: String, started: Int): ByteArray {
        val parent = collectionKey(membership.collectionId)
        requireGeneration(started, null)
        val key = unwrapKey(
            deps.sodium,
            parent,
            membership.entryKeyWrap,
            AdContext.ENTRY_KEY_BY_COLLECTION,
            bindingOf(membership.collectionId, entryId),
        )
        return remember(entryKeys, entryId, started, key)
    }

    private fun resolveCollectionKey(account: AccountKeys, collection: CollectionRow): ByteArray {
        if (collection.kind == CollectionKind.LINK) {
            val linkSecret = unwrapKey(deps.sodium, account.masterKey, collection.keyMaterial, AdContext.LINK_SECRET_BY_MASTER, collection.id)
            try {
                return deps.sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_COLLECTION_KEY, KdfContext.LINK.value, linkSecret)
            } finally {
                wipe(linkSecret)
            }
        }
        if (collection.role == CollectionRole.VIEWER) {
            return openSharePayload(deps.sodium, collection.keyMaterial, account.publicKey, account.privateKey, collection.id)
        }
        return unwrapKey(deps.sodium, account.masterKey, collection.keyMaterial, AdContext.COLLECTION_KEY_BY_MASTER, collection.id)
    }

    private fun requireAccount(): AccountKeys = deps.account() ?: throw AccountLockedException()

    private fun requireGeneration(started: Int, key: ByteArray?) {
        if (started == generation) return
        key?.let(::wipe)
        throw AccountLockedException()
    }

    private fun cached(cache: Map<String, ByteArray>, id: String): ByteArray? = synchronized(lock) { cache[id] }

    private fun remember(cache: MutableMap<String, ByteArray>, id: String, started: Int, key: ByteArray): ByteArray =
        synchronized(lock) {
            requireGeneration(started, key)
            val existing = cache[id]
            if (existing != null) {
                wipe(key)
                existing
            } else {
                cache[id] = key
                key
            }
        }

    private fun dropMediaKey(cacheKey: String) {
        mediaKeys.remove(cacheKey)?.let(::wipe)
    }

    private fun subkeyIdOf(variant: ObjectVariant): Long =
        when (variant) {
            ObjectVariant.ORIGINAL -> KdfSubkeyIds.MEDIA_ORIGINAL
            ObjectVariant.THUMBNAIL -> KdfSubkeyIds.MEDIA_THUMBNAIL
        }
}

internal fun bindingOf(parentId: String, childId: String): String = parentId + EntriesConstants.KEY_BINDING_SEPARATOR + childId

private fun homeFirst(memberships: List<MembershipRow>, kindOf: (String) -> CollectionKind?): List<MembershipRow> {
    val kinds = memberships.associate { it.collectionId to kindOf(it.collectionId) }
    return memberships.sortedByDescending { kinds[it.collectionId] == CollectionKind.HOME }
}
