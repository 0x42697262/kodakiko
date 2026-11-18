package dev.kodakiko.service.entries

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.CoroutineDispatcher

data class BulkResult(val changed: Int, val skipped: Int)

data class CreateEntryInput(
    val note: String,
    val tags: List<String>,
    val time: EntryTimeEdit?,
    val location: OptionalValue<EntryLocation?> = OptionalValue.Absent,
)

interface EntriesSyncPort {
    fun notifyLocalWrite()

    fun isEntryInFlight(entryId: String): Boolean
}

interface EntriesService {
    suspend fun createFromTray(input: CreateEntryInput): String

    suspend fun createFromMedia(input: CreateEntryInput, items: List<ImportedMedia>): String

    suspend fun edit(entryId: String, edit: EntryEdit)

    suspend fun addMedia(entryId: String, items: List<ImportedMedia>)

    suspend fun removeMedia(entryId: String, mediaId: String)

    suspend fun trash(entryIds: List<String>): BulkResult

    suspend fun restore(entryIds: List<String>): BulkResult

    suspend fun deletePermanently(entryIds: List<String>): BulkResult

    suspend fun addTagToMany(entryIds: List<String>, tag: String): BulkResult

    suspend fun removeTagFromMany(entryIds: List<String>, tag: String): BulkResult

    suspend fun setFavorite(entryIds: List<String>, favorite: Boolean): BulkResult

    suspend fun renameTag(entryIds: List<String>, from: String, to: String): BulkResult

    suspend fun deleteTag(entryIds: List<String>, tag: String): BulkResult

    suspend fun readMetadata(entryId: String): EntryMetadataV1
}

class EntriesServiceDeps(
    val sodium: Sodium,
    val rowStore: RowStorePort,
    val files: EntryFiles,
    val entryKeys: EntryKeys,
    val captureTray: CaptureTray,
    val config: () -> PublicConfigDto?,
    val sync: EntriesSyncPort,
    val onEntriesChanged: (List<String>) -> Unit,
    val clock: EntriesClock,
    val randomId: () -> String,
    val userId: () -> String?,
    val ioDispatcher: CoroutineDispatcher,
)
