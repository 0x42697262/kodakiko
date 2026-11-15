package dev.kodakiko.service.stats

import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.js.JsDate
import dev.kodakiko.media.TimezoneOffsetSource
import dev.kodakiko.media.deviceOffsetMinutes
import kotlinx.coroutines.withContext

private val HOME_JOURNAL = GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, emptyList(), null, null, StatsConstants.NO_TEXT)

class DefaultStatsService(private val deps: StatsServiceDeps) : StatsService {
    override fun statistics(): JournalStatistics {
        val index = deps.index()
        val entries: List<IndexedEntry> = index?.query(HOME_JOURNAL).orEmpty().flatMap { it.entryIds }.mapNotNull { index?.get(it) }
        return journalStatistics(entries, todayLocalDate())
    }

    override suspend fun entryDetails(entryId: String): List<DetailSection> {
        val metadata = deps.readMetadata(entryId)
        val (row, objects) = withContext(deps.ioDispatcher) {
            deps.rowStore.transaction { tx ->
                val byMedia = LinkedHashMap<String, List<ObjectRow>>()
                metadata.media.forEach { byMedia[it.mediaId] = tx.objects.byMedia(it.mediaId) }
                tx.entries.get(entryId) to byMedia
            }
        }
        return entryDetailSections(entryId, metadata, row, objects, deps.technical())
    }

    private fun todayLocalDate(): String {
        val now = deps.clock.now()
        val offset = deviceOffsetMinutes(TimezoneOffsetSource { deps.clock.timezoneOffsetMinutes(now) })
        return localDateOf(JsDate.toIsoString(now.toDouble()), offset)
    }
}
