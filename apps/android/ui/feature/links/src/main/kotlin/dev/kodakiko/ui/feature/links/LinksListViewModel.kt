package dev.kodakiko.ui.feature.links

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.links.OwnerLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class LinksListState(val links: List<OwnerLink>?, val revoking: OwnerLink?, val error: Boolean, val nowMs: Long)

sealed interface LinksListAction {
    data class Copy(val link: OwnerLink) : LinksListAction

    data class Share(val link: OwnerLink) : LinksListAction

    data class Revoke(val link: OwnerLink) : LinksListAction

    data object ConfirmRevoke : LinksListAction

    data object DismissRevoke : LinksListAction

    data object Back : LinksListAction
}

sealed interface LinksListEvent {
    data object Back : LinksListEvent
}

private suspend fun succeeded(work: suspend () -> Unit): Boolean =
    try {
        work()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

class LinksListViewModel(private val deps: LinksDeps) : ViewModel() {
    private val mutableState = MutableStateFlow(LinksListState(links = null, revoking = null, error = false, nowMs = deps.nowMs()))
    private val channel = Channel<LinksListEvent>(Channel.BUFFERED)

    val state: StateFlow<LinksListState> = mutableState.asStateFlow()

    val events: Flow<LinksListEvent> = channel.receiveAsFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun onAction(action: LinksListAction) {
        when (action) {
            is LinksListAction.Copy -> deps.sharing.copy(action.link.url)
            is LinksListAction.Share -> deps.sharing.share(action.link.url)
            is LinksListAction.Revoke -> mutableState.update { it.copy(revoking = action.link) }
            LinksListAction.ConfirmRevoke -> confirmRevoke()
            LinksListAction.DismissRevoke -> mutableState.update { it.copy(revoking = null) }
            LinksListAction.Back -> channel.trySend(LinksListEvent.Back)
        }
    }

    private suspend fun reload() {
        val loaded = succeeded {
            val links = deps.links.list()
            mutableState.update { it.copy(links = links, nowMs = deps.nowMs()) }
        }
        if (!loaded) mutableState.update { it.copy(error = true) }
    }

    private fun confirmRevoke() {
        val target = mutableState.value.revoking ?: return
        mutableState.update { it.copy(revoking = null, error = false) }
        viewModelScope.launch {
            if (succeeded { deps.links.revoke(target.linkId) }) reload() else mutableState.update { it.copy(error = true) }
        }
    }
}
