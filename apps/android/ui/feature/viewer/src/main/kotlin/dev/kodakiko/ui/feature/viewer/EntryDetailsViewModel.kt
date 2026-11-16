package dev.kodakiko.ui.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.stats.DetailSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class EntryDetailsViewModel(private val deps: EntryDetailsDeps, private val entryId: String) : ViewModel() {
    private val eventChannel = Channel<EntryDetailsEvent>(Channel.UNLIMITED)
    private val sections = MutableStateFlow(EntryDetailsState(sections = null))

    val state: StateFlow<EntryDetailsState> = sections.asStateFlow()

    val events: Flow<EntryDetailsEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            deps.settings.map { it.showTechnicalDetails }.distinctUntilChanged().collectLatest { load() }
        }
    }

    fun onAction(action: EntryDetailsAction) {
        when (action) {
            EntryDetailsAction.Back -> eventChannel.trySend(EntryDetailsEvent.Back)
        }
    }

    private suspend fun load() {
        val loaded: List<DetailSection> = try {
            deps.stats.entryDetails(entryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        sections.value = EntryDetailsState(sections = loaded)
    }
}
