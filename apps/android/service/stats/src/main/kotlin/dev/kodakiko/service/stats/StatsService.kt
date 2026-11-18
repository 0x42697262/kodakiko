package dev.kodakiko.service.stats

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntriesClock
import kotlinx.coroutines.CoroutineDispatcher

interface StatsService {
    fun statistics(): JournalStatistics

    suspend fun entryDetails(entryId: String): List<DetailSection>
}

class StatsServiceDeps(
    val index: () -> GalleryIndex?,
    val clock: EntriesClock,
    val readMetadata: suspend (String) -> EntryMetadataV1,
    val rowStore: RowStorePort,
    val technical: () -> Boolean,
    val ioDispatcher: CoroutineDispatcher,
)
