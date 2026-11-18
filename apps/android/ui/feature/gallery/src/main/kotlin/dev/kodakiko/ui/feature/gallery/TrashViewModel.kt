package dev.kodakiko.ui.feature.gallery

import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryView
import dev.kodakiko.service.index.IndexVersion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TrashViewModel(deps: GalleryDeps) : GridViewModel(deps, BulkMode.TRASH) {
    private val filter: GalleryFilter = GalleryFilters.EMPTY.copy(view = GalleryView.TRASH)
    private val retentionDays: Long = deps.config.current()?.trashDays ?: GalleryConstants.TRASH_DAYS_FALLBACK

    val state: StateFlow<TrashState> = combine(deps.index.version, deps.settings.settings, deps.account.mode, grid) { version, settings, mode, grid ->
        build(version, settings, mode, grid)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(GalleryConstants.STATE_SUBSCRIPTION_MS),
        build(deps.index.version.value, deps.settings.settings.value, deps.account.mode.value, grid.value),
    )

    fun onAction(action: GalleryAction) {
        onGridAction(action)
    }

    override fun currentFilter(): GalleryFilter = filter

    override fun pullToSyncAvailable(): Boolean = pullAvailable(deps.account.mode.value, deps.settings.settings.value)

    private fun build(version: IndexVersion, settings: DeviceSettings, mode: AccountMode, grid: GridUi): TrashState {
        val opening = opening(version)
        val index = if (opening) null else deps.index.index()
        val sections = GalleryArrangement.gallerySections(index, GalleryArrangement.sections(index, filter, settings, grid.seed), settings)
        return TrashState(
            opening = opening,
            grid = galleryGrid(sections, settings, grid.selection),
            retentionDays = retentionDays,
            pullToSync = pullAvailable(mode, settings),
            refreshing = grid.refreshing,
            sheet = bulkSheet(grid, settings),
            snackbar = grid.snackbar,
        )
    }
}
