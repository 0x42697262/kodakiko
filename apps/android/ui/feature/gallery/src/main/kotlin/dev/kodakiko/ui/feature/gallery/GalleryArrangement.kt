package dev.kodakiko.ui.feature.gallery

import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.domain.localTimeOf
import dev.kodakiko.index.AttributeFilters
import dev.kodakiko.index.DaySection
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryFilterSettings
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GallerySort
import dev.kodakiko.index.GallerySortKey
import dev.kodakiko.index.GroupBy
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.index.TriState
import dev.kodakiko.index.galleryAttributes
import dev.kodakiko.index.groupSections
import dev.kodakiko.index.orderTags
import dev.kodakiko.ui.core.format.formatClock
import dev.kodakiko.data.db.TriState as SettingsTriState

internal object GalleryArrangement {
    fun attributes(settings: DeviceSettings, hideShared: Boolean): AttributeFilters {
        val filters = settings.galleryFilters
        return galleryAttributes(
            GalleryFilterSettings(
                video = triState(filters.video),
                note = triState(filters.note),
                multiple = triState(filters.multiple),
                shared = triState(filters.shared),
                pending = triState(filters.pending),
                keptOffline = triState(filters.keptOffline),
                favorite = triState(filters.favorite),
            ),
            hideShared,
        )
    }

    fun attributesActive(settings: DeviceSettings): Boolean = attributes(settings, hideShared = false).values.any { it != TriState.OFF }

    fun group(settings: DeviceSettings): GalleryGroup = if (settings.galleryGroupByDay) settings.galleryGroupBy else GalleryGroup.NONE

    fun sections(index: GalleryIndex?, filter: GalleryFilter, settings: DeviceSettings, seed: Double): List<DaySection> {
        if (index == null) return emptyList()
        val refined = filter.copy(attributes = attributes(settings, settings.hideShared))
        val sort = GallerySort(GallerySortKey.valueOf(settings.gallerySort.key.name), settings.gallerySort.descending)
        return groupSections(index.query(refined), index::get, sort, GroupBy.valueOf(group(settings).name), seed)
    }

    fun gallerySections(index: GalleryIndex?, sections: List<DaySection>, settings: DeviceSettings): List<GallerySection> {
        if (index == null) return emptyList()
        var position = 0
        return sections.map { section ->
            val tiles = section.entryIds.mapNotNull { id ->
                index.get(id)?.let { entry -> tile(entry, position, settings).also { position += 1 } }
            }
            GallerySection(section.localDate, tiles)
        }
    }

    fun tile(entry: IndexedEntry, position: Int, settings: DeviceSettings): GalleryTile = GalleryTile(
        entryId = entry.id,
        position = position,
        coverMediaId = entry.coverMediaId,
        unreadable = entry.unreadable,
        pending = entry.pending,
        favorite = entry.favorite == true,
        hasVideo = entry.hasVideo,
        hasNote = entry.notePreview.isNotEmpty(),
        extraMedia = maxOf(0, entry.mediaCount - GalleryConstants.SINGLE_MEDIA),
        localDate = entry.localDate,
        time = formatClock(localTimeOf(entry.takenAtUtc, entry.utcOffsetMinutes), settings.timeFormat),
        note = if (settings.hideNotePreviews) "" else entry.notePreview.replace(GalleryConstants.MARKDOWN_LEAD, ""),
        tags = entry.tags,
    )

    fun display(settings: DeviceSettings): GalleryDisplay =
        GalleryDisplay(settings.galleryDisplayMode, settings.galleryColumnsPortrait, settings.galleryColumnsLandscape, settings.galleryBadges)

    fun tagChoices(index: GalleryIndex?, settings: DeviceSettings): List<String> =
        orderTags(index?.allTags().orEmpty(), settings.tagOrder).map { it.tag }

    fun chips(filter: GalleryFilter): List<FilterChip> = buildList {
        filter.tags.forEach { add(FilterChip.Tag(it)) }
        if (filter.fromDate != null || filter.toDate != null) add(FilterChip.Dates(filter.fromDate, filter.toDate))
        val text = filter.text.trim()
        if (text.isNotEmpty()) add(FilterChip.Text(text))
    }

    fun tagTabs(index: GalleryIndex?, filter: GalleryFilter, settings: DeviceSettings): List<TagTab> {
        if (index == null || !settings.galleryShowTagTabs) return emptyList()
        val tags = orderTags(index.allTags(), settings.tagOrder)
        val base = index.query(filter.copy(tags = emptyList(), attributes = null)).flatMap { it.entryIds }
        val favorites = base.count { index.get(it)?.favorite == true }
        if (tags.isEmpty() && favorites == 0) return emptyList()
        val favoriteOnly = settings.galleryFilters.favorite == SettingsTriState.INCLUDE
        val selectedTag = filter.tags.singleOrNull()
        val none = filter.tags.isEmpty()
        return buildList {
            add(TagTab.All(base.size, none && !favoriteOnly))
            if (favorites > 0) add(TagTab.Favorites(favorites, none && favoriteOnly))
            tags.forEachIndexed { position, item -> add(TagTab.Tag(position, item.tag, item.count, item.tag == selectedTag)) }
        }
    }

    fun onThisDay(index: GalleryIndex?, today: String): List<OnThisDayYear> {
        if (index == null) return emptyList()
        return index.onThisDay(today, GalleryScope.Home).map { group ->
            val entries = group.entryIds.take(GalleryConstants.ON_THIS_DAY_TILE_LIMIT).mapNotNull { id ->
                index.get(id)?.let { OnThisDayEntry(it.id, it.coverMediaId, it.unreadable) }
            }
            OnThisDayYear(group.yearsAgo.toInt(), entries)
        }
    }

    private fun triState(value: SettingsTriState): TriState = TriState.valueOf(value.name)
}
