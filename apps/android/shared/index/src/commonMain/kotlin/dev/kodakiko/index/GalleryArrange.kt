package dev.kodakiko.index

import dev.kodakiko.contract.WireEnum
import dev.kodakiko.js.jsLocaleCompare
import dev.kodakiko.js.jsStableSort
import kotlin.math.floor
import kotlin.math.max

enum class GroupBy(override val wire: String) : WireEnum {
    DAY("day"),
    MONTH("month"),
    YEAR("year"),
    TAG("tag"),
    NONE("none"),
}

data class GalleryFilterSettings(
    val video: TriState,
    val note: TriState,
    val multiple: TriState,
    val shared: TriState,
    val pending: TriState,
    val keptOffline: TriState,
    val favorite: TriState,
)

sealed interface GalleryItem {
    data object OnThisDay : GalleryItem

    data class Header(val localDate: String) : GalleryItem

    data class Row(val localDate: String, val entryIds: List<String>, val firstPosition: Int) : GalleryItem
}

private fun bucket(ids: List<String>, keyOf: (String) -> List<String>): List<DaySection> {
    val groups = LinkedHashMap<String, MutableList<String>>()
    for (id in ids) {
        for (key in keyOf(id)) groups.getOrPut(key) { mutableListOf() }.add(id)
    }
    return groups.map { (localDate, entryIds) -> DaySection(localDate, entryIds) }
}

private fun tagOrder(left: DaySection, right: DaySection): Int = when {
    left.localDate == UNTAGGED_GROUP -> SORT_AFTER
    right.localDate == UNTAGGED_GROUP -> SORT_BEFORE
    else -> jsLocaleCompare(left.localDate, right.localDate)
}

fun groupSections(sections: List<DaySection>, get: (String) -> IndexedEntry?, sort: GallerySort, groupBy: GroupBy, seed: Double): List<DaySection> {
    if (groupBy == GroupBy.DAY) return arrangeSections(sections, get, sort, true, seed)
    val flat = arrangeSections(sections, get, sort, false, seed)
    val ids = flat.flatMap { it.entryIds }
    if (groupBy == GroupBy.NONE || ids.isEmpty()) return flat
    if (groupBy == GroupBy.TAG) {
        val groups = bucket(ids) { id ->
            val tags = get(id)?.tags.orEmpty()
            if (tags.isEmpty()) listOf(UNTAGGED_GROUP) else tags.map { "$TAG_GROUP_PREFIX$it" }
        }
        return jsStableSort(groups, ::tagOrder)
    }
    val prefix = if (groupBy == GroupBy.MONTH) MONTH_GROUP_PREFIX else YEAR_GROUP_PREFIX
    val length = if (groupBy == GroupBy.MONTH) ArrangeConstants.MONTH_CHARS else ArrangeConstants.YEAR_CHARS
    val groups = bucket(ids) { id -> listOf(prefix + (get(id)?.localDate ?: "").take(length)) }
    if (sort.key != GallerySortKey.TAKEN_AT) return groups
    return jsStableSort(groups) { left, right ->
        (if (left.localDate < right.localDate) SORT_AFTER else SORT_BEFORE) * (if (sort.descending) SORT_AFTER else SORT_BEFORE)
    }
}

fun arrangeSections(sections: List<DaySection>, get: (String) -> IndexedEntry?, sort: GallerySort, groupByDay: Boolean, seed: Double): List<DaySection> {
    if (groupByDay && sort.key == GallerySortKey.TAKEN_AT) {
        if (sort.descending) return sections
        return sections.reversed().map { DaySection(it.localDate, it.entryIds.reversed()) }
    }
    val ids = sections.flatMap { it.entryIds }
    if (ids.isEmpty()) return emptyList()
    return listOf(DaySection(UNGROUPED_DATE, sortEntryIds(ids, get, sort, seed)))
}

fun galleryAttributes(filters: GalleryFilterSettings, hideShared: Boolean): AttributeFilters = linkedMapOf(
    AttributeKey.VIDEO to filters.video,
    AttributeKey.NOTE to filters.note,
    AttributeKey.MULTIPLE to filters.multiple,
    AttributeKey.SHARED to if (hideShared) TriState.EXCLUDE else filters.shared,
    AttributeKey.PENDING to filters.pending,
    AttributeKey.FAVORITE to filters.favorite,
)

fun gridColumns(width: Double): Int = max(GRID_MIN_COLUMNS.toDouble(), floor(width / GRID_TILE_MIN_PX)).toInt()

fun columnsFor(width: Double, height: Double, portrait: Int, landscape: Int): Int {
    val chosen = if (width > height) landscape else portrait
    return if (chosen == GALLERY_COLUMNS_AUTO) gridColumns(width) else chosen
}

fun buildGalleryItems(sections: List<DaySection>, columns: Int, showOnThisDay: Boolean): List<GalleryItem> {
    val items = mutableListOf<GalleryItem>()
    if (showOnThisDay) items.add(GalleryItem.OnThisDay)
    var position = 0
    for (section in sections) {
        if (section.localDate != UNGROUPED_DATE) items.add(GalleryItem.Header(section.localDate))
        var start = 0
        while (start < section.entryIds.size) {
            val entryIds = section.entryIds.subList(start, minOf(start + columns, section.entryIds.size)).toList()
            items.add(GalleryItem.Row(section.localDate, entryIds, position))
            position += entryIds.size
            start += columns
        }
    }
    return items
}
