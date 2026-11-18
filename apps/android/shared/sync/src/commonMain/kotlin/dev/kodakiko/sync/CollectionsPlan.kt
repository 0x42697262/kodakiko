package dev.kodakiko.sync

import dev.kodakiko.contract.CollectionKind
import dev.kodakiko.contract.CollectionRole

data class RemoteCollectionState(
    val id: String,
    val kind: CollectionKind,
    val role: CollectionRole,
    val lastSeq: Long,
    val tombstonesPrunedThroughSeq: Long,
    val metaUpdatedAt: String,
)

data class LocalCollectionState(val id: String, val cursorSeq: Long, val metaUpdatedAt: String)

data class CollectionsPlan(val refetch: List<String>, val pull: List<String>, val resync: List<String>, val drop: List<String>)

fun planCollections(remote: List<RemoteCollectionState>, local: List<LocalCollectionState>): CollectionsPlan {
    val readable = remote.filter { it.kind != CollectionKind.LINK }
    val remoteIds = readable.map { it.id }.toSet()
    val localById = local.associateBy { it.id }
    val refetch = mutableListOf<String>()
    val pull = mutableListOf<String>()
    val resync = mutableListOf<String>()
    for (collection in readable) {
        val known = localById[collection.id]
        val cursorSeq = known?.cursorSeq ?: 0L
        if (known == null || known.metaUpdatedAt != collection.metaUpdatedAt) refetch.add(collection.id)
        if (known != null && cursorSeq > 0 && cursorSeq < collection.tombstonesPrunedThroughSeq) {
            resync.add(collection.id)
        } else if (collection.lastSeq > cursorSeq) {
            pull.add(collection.id)
        }
    }
    val drop = local.filter { it.id !in remoteIds }.map { it.id }
    return CollectionsPlan(refetch, pull, resync, drop)
}
