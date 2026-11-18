package dev.kodakiko.index

import dev.kodakiko.contract.WireEnum

data class IndexedEntry(
    val id: String,
    val isOwn: Boolean,
    val collectionIds: List<String>,
    val trashed: Boolean,
    val unreadable: Boolean,
    val takenAtUtc: String,
    val utcOffsetMinutes: Long,
    val localDate: String,
    val monthDay: String,
    val tags: List<String>,
    val coverMediaId: String?,
    val mediaCount: Int,
    val hasVideo: Boolean,
    val notePreview: String,
    val pending: Boolean,
    val favorite: Boolean? = null,
    val videoCount: Int? = null,
    val videoMs: Long? = null,
    val originalBytes: Long? = null,
)

sealed interface GalleryScope {
    data object Home : GalleryScope

    data class Collection(val collectionId: String) : GalleryScope
}

enum class GalleryView(override val wire: String) : WireEnum {
    JOURNAL("journal"),
    TRASH("trash"),
}

enum class TriState(override val wire: String) : WireEnum {
    OFF("off"),
    INCLUDE("include"),
    EXCLUDE("exclude"),
}

enum class AttributeKey(override val wire: String) : WireEnum {
    VIDEO("video"),
    NOTE("note"),
    MULTIPLE("multiple"),
    SHARED("shared"),
    PENDING("pending"),
    FAVORITE("favorite"),
}

typealias AttributeFilters = Map<AttributeKey, TriState>

data class GalleryFilter(
    val scope: GalleryScope,
    val view: GalleryView,
    val tags: List<String>,
    val fromDate: String?,
    val toDate: String?,
    val text: String,
    val attributes: AttributeFilters? = null,
)

enum class GallerySortKey(override val wire: String) : WireEnum {
    TAKEN_AT("takenAt"),
    NOTE_LENGTH("noteLength"),
    MEDIA_COUNT("mediaCount"),
    RANDOM("random"),
}

data class GallerySort(val key: GallerySortKey, val descending: Boolean)

data class DaySection(val localDate: String, val entryIds: List<String>)

data class OnThisDayGroup(val yearsAgo: Double, val entryIds: List<String>)

data class TagCount(val tag: String, val count: Int)
