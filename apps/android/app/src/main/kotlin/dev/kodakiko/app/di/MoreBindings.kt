package dev.kodakiko.app.di

import android.content.Context
import androidx.lifecycle.ViewModel
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.more.AppMoreAccount
import dev.kodakiko.app.more.IndexStatisticsJournal
import dev.kodakiko.app.settings.AppSettingsStorage
import dev.kodakiko.app.sync.AppSyncState
import dev.kodakiko.service.account.AccountService
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.stats.StatsService
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.ui.feature.more.MoreDeps
import dev.kodakiko.ui.feature.more.MoreSync
import dev.kodakiko.ui.feature.more.MoreViewModel
import dev.kodakiko.ui.feature.more.StatisticsDeps
import dev.kodakiko.ui.feature.more.StatisticsJournal
import dev.kodakiko.ui.feature.more.StatisticsStorage
import dev.kodakiko.ui.feature.more.StatisticsViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

@BindingContainer
object MoreBindings {
    @Provides
    fun moreDeps(settings: AppDeviceSettings, auth: AuthService, lockControl: LockControl, sync: SyncService): MoreDeps = MoreDeps(
        settings = settings,
        account = AppMoreAccount(auth, lockControl),
        sync = MoreSync { sync.syncNow() },
    )

    @Provides
    @SingleIn(AppScope::class)
    fun statisticsJournal(stats: StatsService, index: IndexService, @ApplicationScope scope: CoroutineScope): StatisticsJournal =
        IndexStatisticsJournal(stats, index, scope)

    @Provides
    fun statisticsDeps(
        context: Context,
        journal: StatisticsJournal,
        syncState: AppSyncState,
        accounts: AccountService,
        config: ConfigService,
        media: AccountMedia,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): StatisticsDeps {
        val storage = AppSettingsStorage(context, media, ioDispatcher)
        return StatisticsDeps(
            journal = journal,
            sync = syncState.status,
            user = accounts.user.flow,
            config = config,
            storage = StatisticsStorage { storage.usage() },
        )
    }

    @Provides
    @IntoMap
    @ViewModelKey(MoreViewModel::class)
    fun more(deps: MoreDeps): ViewModel = MoreViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(StatisticsViewModel::class)
    fun statistics(deps: StatisticsDeps): ViewModel = StatisticsViewModel(deps)
}
