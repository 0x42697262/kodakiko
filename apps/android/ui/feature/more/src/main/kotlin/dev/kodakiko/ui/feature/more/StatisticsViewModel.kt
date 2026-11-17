package dev.kodakiko.ui.feature.more

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.UserDto
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.service.stats.JournalStatistics
import dev.kodakiko.service.sync.SyncStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class StatisticsState(
    val stats: JournalStatistics,
    val usage: CacheUsage?,
    val user: UserDto?,
    val quotaBytes: Long?,
    val sync: SyncStatus,
)

sealed interface StatisticsAction {
    data object Back : StatisticsAction
}

class StatisticsViewModel(private val deps: StatisticsDeps) : ViewModel() {
    private val channel = Channel<MoreEvent>(Channel.BUFFERED)
    private val usage = MutableStateFlow<CacheUsage?>(null)
    private val stats = deps.journal.changes.map { deps.journal.statistics() }

    val events: Flow<MoreEvent> = channel.receiveAsFlow()

    val state: StateFlow<StatisticsState> = combine(stats, usage, deps.user, deps.sync, ::stateOf).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(MoreConstants.STATE_SUBSCRIPTION_MS),
        stateOf(deps.journal.statistics(), usage.value, deps.user.value, deps.sync.value),
    )

    init {
        viewModelScope.launch { usage.value = attempt { deps.storage.usage() } }
    }

    fun onAction(action: StatisticsAction) {
        when (action) {
            StatisticsAction.Back -> channel.trySend(MoreEvent.Back)
        }
    }

    private fun stateOf(stats: JournalStatistics, usage: CacheUsage?, user: UserDto?, sync: SyncStatus): StatisticsState =
        StatisticsState(stats = stats, usage = usage, user = user, quotaBytes = user?.let { it.quotaBytes ?: deps.config.current()?.defaultQuotaBytes }, sync = sync)
}
