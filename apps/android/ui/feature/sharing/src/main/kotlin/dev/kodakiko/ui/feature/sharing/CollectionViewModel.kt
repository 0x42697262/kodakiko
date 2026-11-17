package dev.kodakiko.ui.feature.sharing

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CollectionTile(val entryId: String, val coverMediaId: String?)

@Immutable
sealed interface CollectionSheet {
    data object Menu : CollectionSheet

    data class Rename(val name: String, val busy: Boolean, val error: SharingMessage?) : CollectionSheet

    data class ConfirmDelete(val busy: Boolean, val error: SharingMessage?) : CollectionSheet

    data class ConfirmLeave(val busy: Boolean, val error: SharingMessage?) : CollectionSheet

    data class Actions(val busy: Boolean, val error: SharingMessage?) : CollectionSheet
}

@Immutable
data class CollectionState(
    val loaded: Boolean,
    val journal: SharedJournal?,
    val tiles: List<CollectionTile>,
    val selecting: Boolean,
    val selected: List<String>,
    val sheet: CollectionSheet?,
)

sealed interface CollectionAction {
    data class TileClick(val entryId: String) : CollectionAction

    data class TileLongClick(val entryId: String) : CollectionAction

    data object More : CollectionAction

    data object ShareWith : CollectionAction

    data object Rename : CollectionAction

    data class NameChanged(val name: String) : CollectionAction

    data object Save : CollectionAction

    data object Delete : CollectionAction

    data object ConfirmDelete : CollectionAction

    data object Leave : CollectionAction

    data object ConfirmLeave : CollectionAction

    data object OpenActions : CollectionAction

    data object RemoveFromJournal : CollectionAction

    data object Dismiss : CollectionAction

    data object Back : CollectionAction

    data object ClearSelection : CollectionAction
}

sealed interface CollectionEvent {
    data class Open(val route: Route) : CollectionEvent

    data object Back : CollectionEvent

    data class Removed(val count: Int) : CollectionEvent
}

private class Loaded(val journal: SharedJournal?, val tiles: List<CollectionTile>)

private data class Selection(val selecting: Boolean, val selected: List<String>)

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModel(private val deps: SharingDeps, private val collectionId: String) : ViewModel() {
    private val sheet = MutableStateFlow<CollectionSheet?>(null)
    private val selection = MutableStateFlow(Selection(selecting = false, selected = emptyList()))
    private val reloads = MutableStateFlow(SharingConstants.NO_RELOADS)
    private val channel = Channel<CollectionEvent>(Channel.BUFFERED)

    val events: Flow<CollectionEvent> = channel.receiveAsFlow()

    private val loaded: Flow<Loaded?> = reloadTriggers(deps, reloads).mapLatest { load() }

    val state: StateFlow<CollectionState> = combine(loaded, selection, sheet) { current, chosen, shownSheet ->
        CollectionState(
            loaded = current != null,
            journal = current?.journal,
            tiles = current?.tiles.orEmpty(),
            selecting = chosen.selecting,
            selected = chosen.selected,
            sheet = shownSheet,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SharingConstants.STATE_SUBSCRIPTION_MS),
        CollectionState(loaded = false, journal = null, tiles = emptyList(), selecting = false, selected = emptyList(), sheet = null),
    )

    private val journal: SharedJournal? get() = state.value.journal

    private val isOwner: Boolean get() = journal?.role == CollectionRole.OWNER

    fun onAction(action: CollectionAction) {
        when (action) {
            is CollectionAction.TileClick -> tileClick(action.entryId)
            is CollectionAction.TileLongClick -> tileLongClick(action.entryId)
            CollectionAction.More -> if (journal != null) sheet.value = CollectionSheet.Menu
            CollectionAction.ShareWith -> shareWith()
            CollectionAction.Rename -> rename()
            is CollectionAction.NameChanged -> sheet.update { (it as? CollectionSheet.Rename)?.copy(name = action.name.take(COLLECTION_NAME_MAX_CHARS)) ?: it }
            CollectionAction.Save -> save()
            CollectionAction.Delete -> if (isOwner) sheet.value = CollectionSheet.ConfirmDelete(busy = false, error = null)
            CollectionAction.ConfirmDelete -> confirmDelete()
            CollectionAction.Leave -> if (journal != null && !isOwner) sheet.value = CollectionSheet.ConfirmLeave(busy = false, error = null)
            CollectionAction.ConfirmLeave -> confirmLeave()
            CollectionAction.OpenActions -> openActions()
            CollectionAction.RemoveFromJournal -> removeFromJournal()
            CollectionAction.Dismiss -> dismiss()
            CollectionAction.Back -> back()
            CollectionAction.ClearSelection -> clearSelection()
        }
    }

    private suspend fun load(): Loaded {
        val journals = attempt { loadJournals(deps) }.getOrNull().orEmpty()
        val index = deps.index.index()
        val tiles = collectionEntryIds(index, collectionId).map { CollectionTile(it, index?.get(it)?.coverMediaId) }
        return Loaded(journals.firstOrNull { it.id == collectionId }, tiles)
    }

    private fun tileClick(entryId: String) {
        val current = selection.value
        if (!current.selecting) {
            channel.trySend(CollectionEvent.Open(Route.Entry(entryId)))
            return
        }
        val next = if (entryId in current.selected) current.selected - entryId else current.selected + entryId
        selection.value = current.copy(selected = next)
    }

    private fun tileLongClick(entryId: String) {
        if (!isOwner) return
        selection.update { current -> Selection(selecting = true, selected = if (entryId in current.selected) current.selected else current.selected + entryId) }
    }

    private fun shareWith() {
        if (!isOwner) return
        sheet.value = null
        channel.trySend(CollectionEvent.Open(Route.CollectionShare(collectionId)))
    }

    private fun rename() {
        val current = journal ?: return
        if (current.role != CollectionRole.OWNER) return
        sheet.value = CollectionSheet.Rename(name = (current.name as? CollectionName.Named)?.name.orEmpty(), busy = false, error = null)
    }

    private fun save() {
        val current = sheet.value as? CollectionSheet.Rename ?: return
        val name = jsTrim(current.name)
        if (current.busy || name.isEmpty()) return
        sheet.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            attempt { deps.sharing.renameJournal(collectionId, name) }
                .onSuccess {
                    deps.names.clear()
                    sheet.value = null
                    reloads.update { it + 1 }
                }
                .onFailure { failure -> sheet.update { (it as? CollectionSheet.Rename)?.copy(busy = false, error = sharingMessage(failure)) ?: it } }
        }
    }

    private fun confirmDelete() {
        val current = sheet.value as? CollectionSheet.ConfirmDelete ?: return
        if (current.busy) return
        sheet.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            attempt { deps.sharing.deleteJournal(collectionId) }
                .onSuccess { leaveScreen() }
                .onFailure { failure -> sheet.update { (it as? CollectionSheet.ConfirmDelete)?.copy(busy = false, error = sharingMessage(failure)) ?: it } }
        }
    }

    private fun confirmLeave() {
        val current = sheet.value as? CollectionSheet.ConfirmLeave ?: return
        if (current.busy) return
        sheet.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            attempt { deps.sharing.leave(collectionId) }
                .onSuccess { leaveScreen() }
                .onFailure { failure -> sheet.update { (it as? CollectionSheet.ConfirmLeave)?.copy(busy = false, error = sharingMessage(failure)) ?: it } }
        }
    }

    private fun leaveScreen() {
        sheet.value = null
        channel.trySend(CollectionEvent.Back)
    }

    private fun openActions() {
        val current = selection.value
        if (!isOwner || !current.selecting || current.selected.isEmpty()) return
        sheet.value = CollectionSheet.Actions(busy = false, error = null)
    }

    private fun removeFromJournal() {
        val current = sheet.value as? CollectionSheet.Actions ?: return
        val entryIds = selection.value.selected
        if (current.busy || entryIds.isEmpty()) return
        sheet.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            attempt { deps.sharing.removeEntries(collectionId, entryIds) }
                .onSuccess {
                    sheet.value = null
                    clearSelection()
                    channel.trySend(CollectionEvent.Removed(entryIds.size))
                }
                .onFailure { failure -> sheet.update { (it as? CollectionSheet.Actions)?.copy(busy = false, error = sharingMessage(failure)) ?: it } }
        }
    }

    private fun dismiss() {
        if (sheet.value is CollectionSheet.Actions) clearSelection()
        sheet.value = null
    }

    private fun back() {
        if (selection.value.selecting) {
            sheet.value = null
            clearSelection()
        } else {
            channel.trySend(CollectionEvent.Back)
        }
    }

    private fun clearSelection() {
        selection.value = Selection(selecting = false, selected = emptyList())
    }
}
