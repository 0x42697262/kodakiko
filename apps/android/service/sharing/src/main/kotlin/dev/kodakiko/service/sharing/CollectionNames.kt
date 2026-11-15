package dev.kodakiko.service.sharing

import dev.kodakiko.contract.COLLECTION_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.openPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed interface CollectionName {
    data class Named(val name: String) : CollectionName

    data object Unnamed : CollectionName
}

interface CollectionNames {
    suspend fun get(collectionId: String): CollectionName

    fun clear()
}

class CollectionNamesDeps(
    val sodium: Sodium,
    val rowStore: RowStorePort,
    val entryKeys: EntryKeys,
    val ioDispatcher: CoroutineDispatcher,
)

class DefaultCollectionNames(private val deps: CollectionNamesDeps) : CollectionNames {
    private val cache = ConcurrentHashMap<String, CollectionName>()
    private val lock = Any()

    @Volatile
    private var generation = 0

    override suspend fun get(collectionId: String): CollectionName {
        cache[collectionId]?.let { return it }
        val started = generation
        val name = open(collectionId)
        synchronized(lock) {
            if (started == generation) cache[collectionId] = name
        }
        return name
    }

    override fun clear() {
        synchronized(lock) {
            generation += 1
            cache.clear()
        }
    }

    private suspend fun open(collectionId: String): CollectionName =
        try {
            val row = withContext(deps.ioDispatcher) { deps.rowStore.transaction { tx -> tx.collections.get(collectionId) } }
            if (row == null) {
                CollectionName.Unnamed
            } else {
                val key = deps.entryKeys.collectionKey(collectionId)
                val metadata = openPayload(
                    deps.sodium,
                    key,
                    row.metadataBlob,
                    ContractSchemas.collectionMetadataV1,
                    COLLECTION_METADATA_SCHEMA_VERSION,
                    emptyMap(),
                    AdContext.COLLECTION_METADATA,
                    collectionId,
                )
                CollectionName.Named(metadata.name)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            CollectionName.Unnamed
        }
}
