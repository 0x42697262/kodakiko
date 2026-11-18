package dev.kodakiko.ui.feature.more

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MoreRow(val testTag: String, val route: Route?, @param:StringRes val title: Int) {
    LOCK_NOW(TestTags.More.LOCK_NOW, null, R.string.more_lock_now),
    TAGS(TestTags.More.TAGS, Route.Tags, R.string.more_tags),
    STATISTICS(TestTags.More.STATISTICS, Route.Statistics, R.string.more_statistics),
    TRASH(TestTags.More.TRASH, Route.Trash, R.string.more_trash),
    DATA_STORAGE(TestTags.More.DATA_STORAGE, Route.SettingsStorage, R.string.more_data_storage),
    SETTINGS(TestTags.More.SETTINGS, Route.Settings, R.string.more_settings),
    ABOUT(TestTags.More.ABOUT, Route.SettingsAbout, R.string.more_about),
    HELP(TestTags.More.HELP, null, R.string.more_help),
}

@Immutable
data class MoreState(val hideShared: Boolean, val syncPaused: Boolean, val local: Boolean)

sealed interface MoreAction {
    data class SetHideShared(val on: Boolean) : MoreAction

    data class SetSyncPaused(val on: Boolean) : MoreAction

    data class Open(val row: MoreRow) : MoreAction
}

internal suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

class MoreViewModel(private val deps: MoreDeps) : ViewModel() {
    private val channel = Channel<MoreEvent>(Channel.BUFFERED)

    val events: Flow<MoreEvent> = channel.receiveAsFlow()

    val state: StateFlow<MoreState> = combine(deps.settings.settings, deps.account.mode, ::stateOf).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(MoreConstants.STATE_SUBSCRIPTION_MS),
        stateOf(deps.settings.settings.value, deps.account.mode.value),
    )

    fun onAction(action: MoreAction) {
        when (action) {
            is MoreAction.SetHideShared -> deps.settings.update { it.copy(hideShared = action.on) }
            is MoreAction.SetSyncPaused -> setSyncPaused(action.on)
            is MoreAction.Open -> open(action.row)
        }
    }

    private fun setSyncPaused(paused: Boolean) {
        deps.settings.update { it.copy(syncPaused = paused) }
        if (!paused) viewModelScope.launch { attempt { deps.sync.syncNow() } }
    }

    private fun open(row: MoreRow) {
        when (row) {
            MoreRow.LOCK_NOW -> viewModelScope.launch { attempt { deps.account.lockNow() } }
            MoreRow.HELP -> channel.trySend(MoreEvent.OpenHelp)
            else -> row.route?.let { channel.trySend(MoreEvent.Open(it)) }
        }
    }

    private fun stateOf(settings: DeviceSettings, mode: AccountMode): MoreState =
        MoreState(hideShared = settings.hideShared, syncPaused = settings.syncPaused, local = mode == AccountMode.LOCAL)
}
