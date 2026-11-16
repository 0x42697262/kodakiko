package dev.kodakiko.ui.feature.gallery

import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.GallerySort
import dev.kodakiko.data.db.GallerySortKey
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.index.AttributeKey
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.service.index.IndexVersion
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.floor
import dev.kodakiko.data.db.GalleryFilters as SettingsFilters
import dev.kodakiko.data.db.TriState as SettingsTriState

internal data class GalleryUi(
    val searching: Boolean = false,
    val barsHidden: Boolean = false,
    val draft: FilterDraft? = null,
)

internal data class GalleryContentModel(
    val opening: Boolean,
    val sections: List<GallerySection>,
    val tagTabs: List<TagTab>,
    val chips: List<FilterChip>,
    val filterActive: Boolean,
    val onThisDay: List<OnThisDayYear>,
    val tagChoices: List<String>,
)

class GalleryViewModel(deps: GalleryDeps) : GridViewModel(deps, BulkMode.JOURNAL) {
    private val ui = MutableStateFlow(GalleryUi(searching = deps.filter.filter.value.text.isNotEmpty()))
    private var lastScrollY = 0f

    override val actionsNeedSelection: Boolean = false

    val state: StateFlow<GalleryState> = combine(
        combine(deps.index.version, deps.settings.settings, deps.filter.filter, grid.map { it.seed }.distinctUntilChanged()) { version, settings, filter, seed ->
            content(version, settings, filter, seed)
        },
        deps.settings.settings,
        combine(deps.account.username, deps.account.mode) { username, mode -> username to mode },
        grid,
        ui,
    ) { content, settings, account, grid, ui -> build(content, settings, deps.filter.filter.value, account.first, account.second, grid, ui) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GalleryConstants.STATE_SUBSCRIPTION_MS), currentState())

    init {
        viewModelScope.launch {
            deps.notices.flow.collect { notice -> if (notice != null) consume(notice) }
        }
    }

    fun onAction(action: GalleryAction) {
        if (onGridAction(action)) return
        when (action) {
            GalleryAction.ToggleSearch -> toggleSearch()
            is GalleryAction.SearchText -> setFilter { it.copy(text = action.text) }
            is GalleryAction.RemoveChip -> removeChip(action.chip)
            GalleryAction.ClearAll, GalleryAction.ClearFilters -> clearFilters()
            is GalleryAction.SelectTab -> selectTab(action.tab)
            GalleryAction.OpenSheet -> openSheet()
            is GalleryAction.CycleFilter -> updateSettings { it.copy(galleryFilters = cycle(it.galleryFilters, action.key)) }
            GalleryAction.ChooseTags -> updateDraft { it.copy(choosingTags = true) }
            is GalleryAction.DraftTag -> updateDraft { it.copy(tags = if (action.tag in it.tags) it.tags - action.tag else it.tags + action.tag) }
            GalleryAction.TagsDone -> updateDraft { it.copy(choosingTags = false) }
            GalleryAction.EditTags -> {
                updateDraft { it.copy(choosingTags = false) }
                send(GalleryEvent.Open(Route.Tags))
            }
            is GalleryAction.DraftFrom -> updateDraft { it.copy(from = action.text) }
            is GalleryAction.DraftTo -> updateDraft { it.copy(to = action.text) }
            GalleryAction.ApplyFilter -> applyFilter()
            GalleryAction.ResetFilter -> updateDraft { FilterDraft() }
            is GalleryAction.Sort -> sort(action.key)
            is GalleryAction.DisplayMode -> updateSettings { it.copy(galleryDisplayMode = action.mode) }
            is GalleryAction.Columns -> updateSettings {
                if (action.landscape) it.copy(galleryColumnsLandscape = action.columns) else it.copy(galleryColumnsPortrait = action.columns)
            }
            is GalleryAction.Badge -> updateSettings { it.copy(galleryBadges = badge(it, action.key, action.on)) }
            is GalleryAction.ShowTagTabs -> updateSettings { it.copy(galleryShowTagTabs = action.show) }
            is GalleryAction.ShowCounts -> updateSettings { it.copy(galleryShowCounts = action.show) }
            is GalleryAction.Group -> updateSettings { it.copy(galleryGroupBy = action.group, galleryGroupByDay = action.group != GalleryGroup.NONE) }
            GalleryAction.UndoTrash -> undoTrash()
            is GalleryAction.Scrolled -> scrolled(action.y)
            GalleryAction.Random -> openRandom()
            GalleryAction.LockNow -> quietly { deps.account.lockNow() }
            GalleryAction.SignOut -> quietly { deps.account.signOut() }
            GalleryAction.OpenTrash -> send(GalleryEvent.Open(Route.Trash))
            GalleryAction.Capture -> send(GalleryEvent.Open(Route.Capture()))
            else -> Unit
        }
    }

    override fun currentFilter(): GalleryFilter = deps.filter.filter.value

    override fun pullToSyncAvailable(): Boolean = pullAvailable(deps.account.mode.value, deps.settings.settings.value)

    override fun openActions() {
        ui.update { it.copy(draft = null) }
        super.openActions()
    }

    override fun dismissSheet() {
        ui.update { it.copy(draft = null) }
        super.dismissSheet()
    }

    override fun leaveScreen() {
        super.leaveScreen()
        lastScrollY = 0f
        ui.update { it.copy(draft = null, barsHidden = false) }
    }

    private fun currentState(): GalleryState {
        val settings = deps.settings.settings.value
        val filter = deps.filter.filter.value
        return build(
            content(deps.index.version.value, settings, filter, grid.value.seed),
            settings,
            filter,
            deps.account.username.value,
            deps.account.mode.value,
            grid.value,
            ui.value,
        )
    }

    private fun content(version: IndexVersion, settings: DeviceSettings, filter: GalleryFilter, seed: Double): GalleryContentModel {
        val opening = opening(version)
        val index = if (opening) null else deps.index.index()
        val arranged = GalleryArrangement.sections(index, filter, settings, seed)
        val filterActive = GalleryFilters.isActive(filter) || GalleryArrangement.attributesActive(settings)
        return GalleryContentModel(
            opening = opening,
            sections = GalleryArrangement.gallerySections(index, arranged, settings),
            tagTabs = GalleryArrangement.tagTabs(index, filter, settings),
            chips = GalleryArrangement.chips(filter),
            filterActive = filterActive,
            onThisDay = if (filterActive) emptyList() else GalleryArrangement.onThisDay(index, deps.clock.todayLocalDate()),
            tagChoices = GalleryArrangement.tagChoices(index, settings),
        )
    }

    private fun build(
        content: GalleryContentModel,
        settings: DeviceSettings,
        filter: GalleryFilter,
        username: String,
        mode: AccountMode,
        grid: GridUi,
        ui: GalleryUi,
    ): GalleryState {
        val draft = ui.draft
        val sheet = when {
            draft != null -> GallerySheet.Settings(settings, GalleryArrangement.group(settings), content.tagChoices, draft)
            else -> bulkSheet(grid, settings)
        }
        val empty = when {
            content.opening || content.sections.isNotEmpty() -> null
            content.filterActive -> GalleryEmpty.NO_MATCHES
            else -> GalleryEmpty.EMPTY
        }
        return GalleryState(
            opening = content.opening,
            username = username,
            grid = galleryGrid(content.sections, settings, grid.selection),
            showCounts = settings.galleryShowCounts,
            tagTabs = content.tagTabs,
            searching = ui.searching,
            searchText = filter.text,
            chips = content.chips,
            filterActive = content.filterActive,
            onThisDay = content.onThisDay,
            empty = empty,
            pullToSync = pullAvailable(mode, settings),
            refreshing = grid.refreshing,
            barsHidden = ui.barsHidden && settings.hideBarsOnScroll,
            sheet = sheet,
            snackbar = grid.snackbar,
        )
    }

    private fun consume(notice: GalleryNotice) {
        deps.notices.value = null
        val snackbar = when (notice) {
            is GalleryNotice.Saved -> if (deps.clock.nowMs() - notice.atMs < GalleryConstants.SAVED_NOTICE_MS) GallerySnackbar.Saved else null
            GalleryNotice.Gone -> GallerySnackbar.Gone
            is GalleryNotice.Trashed -> GallerySnackbar.Trashed(notice.entryId)
        }
        if (snackbar != null) grid.update { it.copy(snackbar = snackbar) }
    }

    private fun setFilter(change: (GalleryFilter) -> GalleryFilter) {
        deps.filter.set(change(deps.filter.filter.value))
    }

    private fun updateSettings(change: (DeviceSettings) -> DeviceSettings) {
        deps.settings.update(change)
    }

    private fun updateDraft(change: (FilterDraft) -> FilterDraft) {
        ui.update { current -> current.draft?.let { current.copy(draft = change(it)) } ?: current }
    }

    private fun toggleSearch() {
        val searching = ui.value.searching
        if (searching) setFilter { it.copy(text = "") }
        ui.update { it.copy(searching = !searching) }
    }

    private fun removeChip(chip: FilterChip) {
        when (chip) {
            is FilterChip.Tag -> setFilter { it.copy(tags = it.tags - chip.tag) }
            is FilterChip.Dates -> setFilter { it.copy(fromDate = null, toDate = null) }
            is FilterChip.Text -> setFilter { it.copy(text = "") }
        }
    }

    private fun clearFilters() {
        deps.filter.set(GalleryFilters.EMPTY)
        ui.update { it.copy(searching = false) }
    }

    private fun selectTab(tab: TagTab) {
        setFilter { it.copy(tags = if (tab is TagTab.Tag) listOf(tab.tag) else emptyList()) }
        val favorite = if (tab is TagTab.Favorites) SettingsTriState.INCLUDE else SettingsTriState.OFF
        if (deps.settings.settings.value.galleryFilters.favorite != favorite) {
            updateSettings { it.copy(galleryFilters = it.galleryFilters.copy(favorite = favorite)) }
        }
    }

    private fun openSheet() {
        val filter = deps.filter.filter.value
        grid.update { it.copy(bulk = null) }
        ui.update { it.copy(draft = FilterDraft(tags = filter.tags, from = filter.fromDate.orEmpty(), to = filter.toDate.orEmpty())) }
    }

    private fun applyFilter() {
        val draft = ui.value.draft ?: return
        val from = draft.from.trim()
        val to = draft.to.trim()
        val problem = GalleryFilters.validateDateRange(from, to)
        if (problem != null) {
            updateDraft { it.copy(problem = problem) }
            return
        }
        setFilter { it.copy(tags = draft.tags, fromDate = from.ifEmpty { null }, toDate = to.ifEmpty { null }) }
        ui.update { it.copy(draft = null) }
    }

    private fun sort(key: GallerySortKey) {
        if (key == GallerySortKey.RANDOM) grid.update { it.copy(seed = it.seed + GalleryConstants.SEED_STEP) }
        updateSettings { settings ->
            val current = settings.gallerySort
            val descending = if (current.key == key) !current.descending else current.descending
            settings.copy(gallerySort = GallerySort(key, descending))
        }
    }

    private fun undoTrash() {
        val trashed = grid.value.snackbar as? GallerySnackbar.Trashed
        grid.update { it.copy(snackbar = null) }
        if (trashed != null) quietly { deps.entries.restore(listOf(trashed.entryId)) }
    }

    private fun scrolled(y: Float) {
        val hides = GalleryBars.scrollDirectionHides(lastScrollY, y) ?: return
        lastScrollY = y
        if (deps.settings.settings.value.hideBarsOnScroll) ui.update { it.copy(barsHidden = hides) }
    }

    private fun openRandom() {
        val order = currentOrder()
        if (order.isEmpty()) return
        val pick = order[floor(deps.random() * order.size).toInt().coerceIn(0, order.lastIndex)]
        send(GalleryEvent.Open(Route.Entry(pick)))
    }

    private fun quietly(work: suspend () -> Unit) {
        viewModelScope.launch(deps.dispatcher) {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    private fun cycle(filters: SettingsFilters, key: AttributeKey): SettingsFilters = when (key) {
        AttributeKey.VIDEO -> filters.copy(video = next(filters.video))
        AttributeKey.NOTE -> filters.copy(note = next(filters.note))
        AttributeKey.MULTIPLE -> filters.copy(multiple = next(filters.multiple))
        AttributeKey.SHARED -> filters.copy(shared = next(filters.shared))
        AttributeKey.PENDING -> filters.copy(pending = next(filters.pending))
        AttributeKey.FAVORITE -> filters.copy(favorite = next(filters.favorite))
    }

    private fun next(state: SettingsTriState): SettingsTriState = when (state) {
        SettingsTriState.OFF -> SettingsTriState.INCLUDE
        SettingsTriState.INCLUDE -> SettingsTriState.EXCLUDE
        SettingsTriState.EXCLUDE -> SettingsTriState.OFF
    }

    private fun badge(settings: DeviceSettings, key: BadgeKey, on: Boolean) = when (key) {
        BadgeKey.FAVORITE -> settings.galleryBadges.copy(favorite = on)
        BadgeKey.VIDEO -> settings.galleryBadges.copy(video = on)
        BadgeKey.PHOTO_COUNT -> settings.galleryBadges.copy(photoCount = on)
        BadgeKey.NOTE -> settings.galleryBadges.copy(note = on)
        BadgeKey.PENDING -> settings.galleryBadges.copy(pending = on)
    }
}
