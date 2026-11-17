package dev.kodakiko.service.index

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.ObjectRow

enum class IndexStatus(val wire: String) {
    IDLE("idle"),
    BUILDING("building"),
    READY("ready"),
}

data class IndexBuildReport(val entries: Int, val unreadable: Int, val durationMs: Long)

data class IndexVersion(val status: IndexStatus, val generation: Int, val version: Int)

data class IndexedRowsCount(val entries: Int, val unreadable: Int)

data class IndexRows(
    val entries: List<EntryRow>,
    val memberships: List<MembershipRow>,
    val media: List<MediaRow>,
    val objects: List<ObjectRow>,
)

class AccountLockedException : IllegalStateException()

typealias MetadataOpener = suspend (EntryRow) -> EntryMetadataV1

fun interface EntryKeySource {
    suspend fun entryKey(entryId: String): ByteArray

    fun release(key: ByteArray) = Unit
}

fun interface EntriesChangedSource {
    fun onEntriesChanged(listener: (List<String>) -> Unit): () -> Unit
}
