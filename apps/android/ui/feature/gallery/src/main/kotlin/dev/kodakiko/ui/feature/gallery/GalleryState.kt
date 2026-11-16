package dev.kodakiko.ui.feature.gallery

import androidx.compose.runtime.Immutable
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.GalleryBadges
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.GallerySortKey
import dev.kodakiko.index.AttributeKey
import dev.kodakiko.ui.core.navigation.Route

@Immutable
data class GalleryTile(
    val entryId: String,
    val position: Int,
    val coverMediaId: String?,
    val unreadable: Boolean,
    val pending: Boolean,
    val favorite: Boolean,
    val hasVideo: Boolean,
    val hasNote: Boolean,
    val extraMedia: Int,
    val localDate: String,
    val time: String,
    val note: String,
    val tags: List<String>,
)

@Immutable
data class GallerySection(val key: String, val tiles: List<GalleryTile>)

@Immutable
data class GalleryDisplay(
    val mode: GalleryDisplayMode,
    val columnsPortrait: Int,
    val columnsLandscape: Int,
    val badges: GalleryBadges,
)

@Immutable
data class GallerySelection(val active: Boolean = false, val ids: List<String> = emptyList())

@Immutable
data class GalleryGrid(
    val sections: List<GallerySection>,
    val today: String,
    val display: GalleryDisplay,
    val selection: GallerySelection,
) {
    val order: List<String> get() = sections.flatMap { section -> section.tiles.map { it.entryId } }
}

@Immutable
sealed interface TagTab {
    val count: Int
    val selected: Boolean

    data class All(override val count: Int, override val selected: Boolean) : TagTab

    data class Favorites(override val count: Int, override val selected: Boolean) : TagTab

    data class Tag(val position: Int, val tag: String, override val count: Int, override val selected: Boolean) : TagTab
}

@Immutable
sealed interface FilterChip {
    data class Tag(val tag: String) : FilterChip

    data class Dates(val from: String?, val to: String?) : FilterChip

    data class Text(val text: String) : FilterChip
}

@Immutable
data class OnThisDayEntry(val entryId: String, val coverMediaId: String?, val unreadable: Boolean)

@Immutable
data class OnThisDayYear(val yearsAgo: Int, val entries: List<OnThisDayEntry>)

enum class GalleryEmpty {
    EMPTY,
    NO_MATCHES,
}

enum class BadgeKey {
    FAVORITE,
    VIDEO,
    PHOTO_COUNT,
    NOTE,
    PENDING,
}

enum class BulkMode {
    JOURNAL,
    TRASH,
}

enum class BulkPanel {
    MENU,
    ADD_TAG,
    REMOVE_TAG,
    CONFIRM_DELETE,
}

@Immutable
data class FilterDraft(
    val tags: List<String> = emptyList(),
    val from: String = "",
    val to: String = "",
    val choosingTags: Boolean = false,
    val problem: DateRangeProblem? = null,
)

@Immutable
data class BulkSheet(
    val mode: BulkMode,
    val panel: BulkPanel,
    val count: Int,
    val tagChoices: List<String>,
    val pickedTag: String? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

@Immutable
sealed interface GallerySheet {
    data class Settings(
        val settings: DeviceSettings,
        val group: GalleryGroup,
        val tagChoices: List<String>,
        val draft: FilterDraft,
    ) : GallerySheet

    data class Actions(val bulk: BulkSheet) : GallerySheet
}

@Immutable
sealed interface GallerySnackbar {
    data object Saved : GallerySnackbar

    data object Gone : GallerySnackbar

    data class Trashed(val entryId: String) : GallerySnackbar

    data class BulkDone(val changed: Int, val skipped: Int) : GallerySnackbar
}

@Immutable
data class GalleryState(
    val opening: Boolean = false,
    val username: String = "",
    val grid: GalleryGrid,
    val showCounts: Boolean = false,
    val tagTabs: List<TagTab> = emptyList(),
    val searching: Boolean = false,
    val searchText: String = "",
    val chips: List<FilterChip> = emptyList(),
    val filterActive: Boolean = false,
    val onThisDay: List<OnThisDayYear> = emptyList(),
    val empty: GalleryEmpty? = null,
    val pullToSync: Boolean = false,
    val refreshing: Boolean = false,
    val barsHidden: Boolean = false,
    val sheet: GallerySheet? = null,
    val snackbar: GallerySnackbar? = null,
)

@Immutable
data class TrashState(
    val opening: Boolean = false,
    val grid: GalleryGrid,
    val retentionDays: Long,
    val pullToSync: Boolean = false,
    val refreshing: Boolean = false,
    val sheet: GallerySheet? = null,
    val snackbar: GallerySnackbar? = null,
)

@Immutable
data class DayState(
    val date: String,
    val grid: GalleryGrid,
    val opening: Boolean = false,
    val pullToSync: Boolean = false,
    val refreshing: Boolean = false,
    val sheet: GallerySheet? = null,
    val snackbar: GallerySnackbar? = null,
)

sealed interface GalleryAction {
    data class TileClick(val entryId: String) : GalleryAction

    data class TileLongClick(val entryId: String) : GalleryAction

    data class OpenEntry(val entryId: String) : GalleryAction

    data object SelectAll : GalleryAction

    data object InvertSelection : GalleryAction

    data object ClearSelection : GalleryAction

    data object OpenActions : GalleryAction

    data object ToggleSearch : GalleryAction

    data class SearchText(val text: String) : GalleryAction

    data class RemoveChip(val chip: FilterChip) : GalleryAction

    data object ClearAll : GalleryAction

    data object ClearFilters : GalleryAction

    data class SelectTab(val tab: TagTab) : GalleryAction

    data object OpenSheet : GalleryAction

    data object DismissSheet : GalleryAction

    data class CycleFilter(val key: AttributeKey) : GalleryAction

    data object ChooseTags : GalleryAction

    data class DraftTag(val tag: String) : GalleryAction

    data object TagsDone : GalleryAction

    data object EditTags : GalleryAction

    data class DraftFrom(val text: String) : GalleryAction

    data class DraftTo(val text: String) : GalleryAction

    data object ApplyFilter : GalleryAction

    data object ResetFilter : GalleryAction

    data class Sort(val key: GallerySortKey) : GalleryAction

    data class DisplayMode(val mode: GalleryDisplayMode) : GalleryAction

    data class Columns(val landscape: Boolean, val columns: Int) : GalleryAction

    data class Badge(val key: BadgeKey, val on: Boolean) : GalleryAction

    data class ShowTagTabs(val show: Boolean) : GalleryAction

    data class ShowCounts(val show: Boolean) : GalleryAction

    data class Group(val group: GalleryGroup) : GalleryAction

    data object BulkAddTag : GalleryAction

    data object BulkRemoveTag : GalleryAction

    data class BulkPickTag(val tag: String) : GalleryAction

    data object BulkConfirmTag : GalleryAction

    data object BulkCancel : GalleryAction

    data class BulkFavorite(val favorite: Boolean) : GalleryAction

    data object BulkTrash : GalleryAction

    data object BulkShare : GalleryAction

    data object BulkRestore : GalleryAction

    data object BulkDeletePermanently : GalleryAction

    data object BulkConfirmDelete : GalleryAction

    data object UndoTrash : GalleryAction

    data object SnackbarDismissed : GalleryAction

    data object PullToSync : GalleryAction

    data class Scrolled(val y: Float) : GalleryAction

    data object Random : GalleryAction

    data object LockNow : GalleryAction

    data object SignOut : GalleryAction

    data object OpenTrash : GalleryAction

    data object Capture : GalleryAction

    data object Back : GalleryAction

    data object ScreenLeft : GalleryAction
}

sealed interface GalleryEvent {
    data class Open(val route: Route) : GalleryEvent

    data class Share(val entryIds: List<String>) : GalleryEvent

    data object Back : GalleryEvent
}
