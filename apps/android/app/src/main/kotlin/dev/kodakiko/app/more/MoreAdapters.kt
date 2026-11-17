package dev.kodakiko.app.more

import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.stats.JournalStatistics
import dev.kodakiko.service.stats.StatsService
import dev.kodakiko.ui.feature.more.MoreAccount
import dev.kodakiko.ui.feature.more.StatisticsJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

class AppMoreAccount(auth: AuthService, private val lockControl: LockControl) : MoreAccount {
    override val mode: StateFlow<AccountMode> = auth.mode.flow

    override suspend fun lockNow() {
        lockControl.lockNow()
    }
}

class IndexStatisticsJournal(private val stats: StatsService, index: IndexService, scope: CoroutineScope) : StatisticsJournal {
    override val changes: StateFlow<Int> = index.version.drop(1).runningFold(0) { count, _ -> count + 1 }.stateIn(scope, SharingStarted.Eagerly, 0)

    override fun statistics(): JournalStatistics = stats.statistics()
}
