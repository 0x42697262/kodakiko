package dev.kodakiko.ui.feature.more

import dev.kodakiko.contract.UserDto
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.stats.JournalStatistics
import dev.kodakiko.service.sync.SyncStatus
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.flow.StateFlow

interface MoreSettings {
    val settings: StateFlow<DeviceSettings>

    fun update(change: (DeviceSettings) -> DeviceSettings)
}

interface MoreAccount {
    val mode: StateFlow<AccountMode>

    suspend fun lockNow()
}

fun interface MoreSync {
    suspend fun syncNow()
}

class MoreDeps(
    val settings: MoreSettings,
    val account: MoreAccount,
    val sync: MoreSync,
)

interface StatisticsJournal {
    val changes: StateFlow<Int>

    fun statistics(): JournalStatistics
}

fun interface StatisticsStorage {
    suspend fun usage(): CacheUsage
}

class StatisticsDeps(
    val journal: StatisticsJournal,
    val sync: StateFlow<SyncStatus>,
    val user: StateFlow<UserDto?>,
    val config: ConfigService,
    val storage: StatisticsStorage,
)

sealed interface MoreEvent {
    data class Open(val route: Route) : MoreEvent

    data object OpenHelp : MoreEvent

    data object Back : MoreEvent
}
