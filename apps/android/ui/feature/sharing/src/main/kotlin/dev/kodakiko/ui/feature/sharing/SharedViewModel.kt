package dev.kodakiko.ui.feature.sharing

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.js.jsTrim
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SharedAvailability {
    ENABLED,
    FLAG_OFF,
    LOCAL_ONLY,
}

@Immutable
sealed interface SharedDialog {
    data class New(val name: String, val busy: Boolean, val error: SharingMessage?) : SharedDialog
}

@Immutable
data class SharedState(
    val availability: SharedAvailability,
    val mine: List<SharedJournal>?,
    val withMe: List<SharedJournal>?,
    val dialog: SharedDialog?,
    val refreshing: Boolean,
)

sealed interface SharedAction {
    data class Open(val collectionId: String) : SharedAction

    data object New : SharedAction

    data class NameChanged(val name: String) : SharedAction

    data object Create : SharedAction

    data object Dismiss : SharedAction

    data object Refresh : SharedAction
}

sealed interface SharedEvent {
    data class Open(val route: Route) : SharedEvent
}

internal fun availabilityOf(mode: AccountMode, config: PublicConfigDto?): SharedAvailability = when {
    mode == AccountMode.LOCAL -> SharedAvailability.LOCAL_ONLY
    config?.sharingEnabled == true -> SharedAvailability.ENABLED
    else -> SharedAvailability.FLAG_OFF
}

@OptIn(ExperimentalCoroutinesApi::class)
class SharedViewModel(private val deps: SharingDeps) : ViewModel() {
    private val dialog = MutableStateFlow<SharedDialog?>(null)
    private val refreshing = MutableStateFlow(false)
    private val reloads = MutableStateFlow(SharingConstants.NO_RELOADS)
    private val channel = Channel<SharedEvent>(Channel.BUFFERED)

    val events: Flow<SharedEvent> = channel.receiveAsFlow()

    private val availability: Flow<SharedAvailability> = combine(deps.accountMode, configFlow(deps.config)) { mode, config -> availabilityOf(mode, config) }.distinctUntilChanged()

    private val journals: Flow<List<SharedJournal>?> = availability.flatMapLatest { current ->
        if (current != SharedAvailability.ENABLED) {
            flowOf(null)
        } else {
            reloadTriggers(deps, reloads).mapLatest { attempt { loadJournals(deps) }.getOrNull() }.onStart { emit(null) }
        }
    }

    val state: StateFlow<SharedState> = combine(availability, journals, dialog, refreshing) { current, loaded, shownDialog, busy ->
        SharedState(
            availability = current,
            mine = loaded?.filter { it.role == CollectionRole.OWNER },
            withMe = loaded?.filter { it.role != CollectionRole.OWNER },
            dialog = shownDialog,
            refreshing = busy,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SharingConstants.STATE_SUBSCRIPTION_MS),
        SharedState(availabilityOf(deps.accountMode.value, deps.config.current()), null, null, null, false),
    )

    fun onAction(action: SharedAction) {
        when (action) {
            is SharedAction.Open -> channel.trySend(SharedEvent.Open(Route.Collection(action.collectionId)))
            SharedAction.New -> dialog.value = SharedDialog.New(name = "", busy = false, error = null)
            is SharedAction.NameChanged -> dialog.update { (it as? SharedDialog.New)?.copy(name = action.name.take(COLLECTION_NAME_MAX_CHARS)) ?: it }
            SharedAction.Create -> create()
            SharedAction.Dismiss -> dialog.value = null
            SharedAction.Refresh -> refresh()
        }
    }

    private fun create() {
        val current = dialog.value as? SharedDialog.New ?: return
        val name = jsTrim(current.name)
        if (current.busy || name.isEmpty()) return
        dialog.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            attempt { deps.sharing.createJournal(name) }
                .onSuccess { id ->
                    dialog.value = null
                    reloads.update { it + 1 }
                    channel.trySend(SharedEvent.Open(Route.Collection(id)))
                }
                .onFailure { failure -> dialog.update { (it as? SharedDialog.New)?.copy(busy = false, error = sharingMessage(failure)) ?: it } }
        }
    }

    private fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            attempt { deps.syncNow() }
            refreshing.value = false
        }
    }
}
