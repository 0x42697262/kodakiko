package dev.kodakiko.ui.feature.sharing

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.ShareDto
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.sharing.Recipient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FoundRecipient(val username: String, val fingerprint: String)

@Immutable
data class ShareWithState(
    val username: String,
    val found: FoundRecipient?,
    val recipients: List<ShareDto>,
    val stopping: ShareDto?,
    val busy: Boolean,
    val error: SharingMessage?,
)

sealed interface ShareWithAction {
    data class UsernameChanged(val username: String) : ShareWithAction

    data object Find : ShareWithAction

    data object ConfirmShare : ShareWithAction

    data object CancelShare : ShareWithAction

    data class Remove(val recipient: ShareDto) : ShareWithAction

    data object ConfirmStop : ShareWithAction

    data object DismissStop : ShareWithAction

    data object Back : ShareWithAction
}

sealed interface ShareWithEvent {
    data object Back : ShareWithEvent
}

class ShareWithViewModel(private val deps: SharingDeps, private val collectionId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(ShareWithState(username = "", found = null, recipients = emptyList(), stopping = null, busy = false, error = null))
    private val channel = Channel<ShareWithEvent>(Channel.BUFFERED)
    private var recipient: Recipient? = null

    val state: StateFlow<ShareWithState> = mutableState.asStateFlow()

    val events: Flow<ShareWithEvent> = channel.receiveAsFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun onAction(action: ShareWithAction) {
        when (action) {
            is ShareWithAction.UsernameChanged -> {
                recipient = null
                mutableState.update { it.copy(username = action.username, found = null) }
            }
            ShareWithAction.Find -> find()
            ShareWithAction.ConfirmShare -> confirmShare()
            ShareWithAction.CancelShare -> {
                recipient = null
                mutableState.update { it.copy(found = null) }
            }
            is ShareWithAction.Remove -> mutableState.update { it.copy(stopping = action.recipient) }
            ShareWithAction.ConfirmStop -> confirmStop()
            ShareWithAction.DismissStop -> mutableState.update { it.copy(stopping = null) }
            ShareWithAction.Back -> channel.trySend(ShareWithEvent.Back)
        }
    }

    private suspend fun reload() {
        attempt { deps.sharing.listShares(collectionId) }
            .onSuccess { recipients -> mutableState.update { it.copy(recipients = recipients) } }
            .onFailure { failure -> mutableState.update { it.copy(error = sharingMessage(failure)) } }
    }

    private fun perform(recipientName: String = "", work: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { work() }.onFailure { failure -> mutableState.update { it.copy(error = sharingMessage(failure, recipientName)) } }
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun find() {
        val username = mutableState.value.username
        if (jsTrim(username).isEmpty()) return
        perform {
            val found = deps.sharing.lookupRecipient(username)
            recipient = found
            mutableState.update { it.copy(found = FoundRecipient(found.username, found.fingerprint)) }
        }
    }

    private fun confirmShare() {
        val target = recipient ?: return
        perform(target.username) {
            deps.sharing.share(collectionId, target)
            recipient = null
            mutableState.update { it.copy(found = null, username = "") }
            reload()
        }
    }

    private fun confirmStop() {
        val target = mutableState.value.stopping ?: return
        perform {
            deps.sharing.unshare(collectionId, target.recipientUserId)
            mutableState.update { it.copy(stopping = null) }
            reload()
        }
    }
}
