package dev.kodakiko.ui.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class EntryNewViewModel(private val deps: EntryNewDeps) : ViewModel() {
    private val eventChannel = Channel<EntryNewEvent>(Channel.UNLIMITED)
    private val current = MutableStateFlow(EntryNewState(failed = false))

    val events: Flow<EntryNewEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<EntryNewState> = current.asStateFlow()

    init {
        viewModelScope.launch { create() }
    }

    private suspend fun create() {
        if (deps.trayIsEmpty()) {
            eventChannel.trySend(EntryNewEvent.Replace(Route.Capture()))
            return
        }
        val entryId = try {
            deps.entries.createFromTray(CreateEntryInput(note = "", tags = emptyList(), time = null))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            current.value = EntryNewState(failed = true)
            return
        }
        eventChannel.trySend(EntryNewEvent.Replace(Route.EntryEdit(entryId, isNew = true)))
    }
}
