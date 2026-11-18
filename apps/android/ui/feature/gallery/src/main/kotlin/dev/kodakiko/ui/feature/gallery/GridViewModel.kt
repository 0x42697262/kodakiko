package dev.kodakiko.ui.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.entries.BulkResult
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class BulkUi(
    val panel: BulkPanel = BulkPanel.MENU,
    val pickedTag: String? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

internal data class GridUi(
    val selection: GallerySelection = GallerySelection(),
    val bulk: BulkUi? = null,
    val snackbar: GallerySnackbar? = null,
    val refreshing: Boolean = false,
    val seed: Double = GalleryConstants.INITIAL_SEED,
)

abstract class GridViewModel internal constructor(
    protected val deps: GalleryDeps,
    private val bulkMode: BulkMode,
) : ViewModel() {
    private val eventChannel = Channel<GalleryEvent>(Channel.UNLIMITED)
    private var acted = false

    internal val grid = MutableStateFlow(GridUi())

    val events: Flow<GalleryEvent> = eventChannel.receiveAsFlow()

    protected open val clearsSelectionAfterAction: Boolean = false

    protected open val actionsNeedSelection: Boolean = true

    protected abstract fun currentFilter(): GalleryFilter

    protected fun send(event: GalleryEvent) {
        eventChannel.trySend(event)
    }

    protected fun opening(version: IndexVersion): Boolean = version.status != IndexStatus.READY || deps.index.index() == null

    protected fun pullAvailable(mode: AccountMode, settings: DeviceSettings): Boolean = mode == AccountMode.SERVER && !settings.syncPaused

    internal fun currentOrder(): List<String> {
        val index = deps.index.index() ?: return emptyList()
        return GalleryArrangement.sections(index, currentFilter(), deps.settings.settings.value, grid.value.seed).flatMap { it.entryIds }
    }

    internal fun bulkSheet(ui: GridUi, settings: DeviceSettings): GallerySheet.Actions? {
        val bulk = ui.bulk ?: return null
        return GallerySheet.Actions(
            BulkSheet(
                mode = bulkMode,
                panel = bulk.panel,
                count = ui.selection.ids.size,
                tagChoices = GalleryArrangement.tagChoices(deps.index.index(), settings),
                pickedTag = bulk.pickedTag,
                busy = bulk.busy,
                failed = bulk.failed,
            ),
        )
    }

    internal fun galleryGrid(sections: List<GallerySection>, settings: DeviceSettings, selection: GallerySelection): GalleryGrid =
        GalleryGrid(sections, deps.clock.todayLocalDate(), GalleryArrangement.display(settings), selection)

    protected open fun onGridAction(action: GalleryAction): Boolean {
        when (action) {
            is GalleryAction.TileClick -> tileClick(action.entryId)
            is GalleryAction.TileLongClick -> tileLongClick(action.entryId)
            is GalleryAction.OpenEntry -> send(GalleryEvent.Open(Route.Entry(action.entryId)))
            GalleryAction.SelectAll -> setSelection(currentOrder().distinct())
            GalleryAction.InvertSelection -> invertSelection()
            GalleryAction.ClearSelection -> grid.update { it.copy(selection = GallerySelection()) }
            GalleryAction.OpenActions -> openActions()
            GalleryAction.BulkAddTag -> updateBulk { it.copy(panel = BulkPanel.ADD_TAG, pickedTag = null) }
            GalleryAction.BulkRemoveTag -> updateBulk { it.copy(panel = BulkPanel.REMOVE_TAG, pickedTag = null) }
            is GalleryAction.BulkPickTag -> updateBulk { it.copy(pickedTag = action.tag) }
            GalleryAction.BulkConfirmTag -> confirmTag()
            GalleryAction.BulkCancel -> updateBulk { it.copy(panel = BulkPanel.MENU, pickedTag = null) }
            is GalleryAction.BulkFavorite -> runBulk { ids -> deps.entries.setFavorite(ids, action.favorite) }
            GalleryAction.BulkTrash -> runBulk { ids -> deps.entries.trash(ids) }
            GalleryAction.BulkRestore -> runBulk { ids -> deps.entries.restore(ids) }
            GalleryAction.BulkDeletePermanently -> updateBulk { it.copy(panel = BulkPanel.CONFIRM_DELETE) }
            GalleryAction.BulkConfirmDelete -> runBulk { ids -> deps.entries.deletePermanently(ids) }
            GalleryAction.BulkShare -> send(GalleryEvent.Share(grid.value.selection.ids))
            GalleryAction.DismissSheet -> dismissSheet()
            GalleryAction.SnackbarDismissed -> grid.update { it.copy(snackbar = null) }
            GalleryAction.PullToSync -> pullToSync()
            GalleryAction.Back -> send(GalleryEvent.Back)
            GalleryAction.ScreenLeft -> leaveScreen()
            else -> return false
        }
        return true
    }

    protected open fun leaveScreen() {
        acted = false
        grid.update { it.copy(selection = GallerySelection(), bulk = null) }
    }

    protected open fun dismissSheet() {
        val clear = acted
        acted = false
        grid.update { ui -> ui.copy(bulk = null, selection = if (clear) GallerySelection() else ui.selection) }
    }

    protected open fun openActions() {
        if (actionsNeedSelection && grid.value.selection.ids.isEmpty()) return
        acted = false
        grid.update { it.copy(bulk = BulkUi()) }
    }

    protected abstract fun pullToSyncAvailable(): Boolean

    private fun pullToSync() {
        if (!pullToSyncAvailable() || grid.value.refreshing) return
        deps.haptics(HapticKind.REFRESH)
        grid.update { it.copy(refreshing = true) }
        viewModelScope.launch(deps.dispatcher) {
            try {
                deps.sync.syncNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                grid.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun tileClick(entryId: String) {
        val selection = grid.value.selection
        if (!selection.active) {
            send(GalleryEvent.Open(Route.Entry(entryId)))
            return
        }
        val ids = if (entryId in selection.ids) selection.ids - entryId else selection.ids + entryId
        grid.update { it.copy(selection = GallerySelection(active = true, ids = ids)) }
    }

    private fun tileLongClick(entryId: String) {
        if (grid.value.selection.active) return
        deps.haptics(HapticKind.SELECT)
        grid.update { it.copy(selection = GallerySelection(active = true, ids = listOf(entryId))) }
    }

    private fun setSelection(ids: List<String>) {
        grid.update { it.copy(selection = GallerySelection(active = true, ids = ids)) }
    }

    private fun invertSelection() {
        val selected = grid.value.selection.ids.toSet()
        setSelection(currentOrder().distinct().filter { it !in selected })
    }

    private fun updateBulk(change: (BulkUi) -> BulkUi) {
        grid.update { ui -> ui.bulk?.let { ui.copy(bulk = change(it)) } ?: ui }
    }

    private fun confirmTag() {
        val bulk = grid.value.bulk ?: return
        val tag = bulk.pickedTag ?: return
        when (bulk.panel) {
            BulkPanel.ADD_TAG -> runBulk { ids -> deps.entries.addTagToMany(ids, tag) }
            BulkPanel.REMOVE_TAG -> runBulk { ids -> deps.entries.removeTagFromMany(ids, tag) }
            else -> Unit
        }
    }

    private fun runBulk(work: suspend (List<String>) -> BulkResult) {
        val bulk = grid.value.bulk ?: return
        if (bulk.busy) return
        val ids = grid.value.selection.ids
        updateBulk { it.copy(busy = true) }
        viewModelScope.launch(deps.dispatcher) {
            val result = try {
                work(ids)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result == null) {
                updateBulk { it.copy(busy = false, failed = true) }
                return@launch
            }
            acted = true
            grid.update { ui ->
                ui.copy(
                    bulk = ui.bulk?.copy(panel = BulkPanel.MENU, pickedTag = null, busy = false, failed = false),
                    snackbar = GallerySnackbar.BulkDone(result.changed, result.skipped),
                    selection = if (clearsSelectionAfterAction) GallerySelection() else ui.selection,
                )
            }
        }
    }
}
