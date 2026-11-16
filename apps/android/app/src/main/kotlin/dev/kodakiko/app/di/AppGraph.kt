package dev.kodakiko.app.di

import android.app.Application
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.AppStartup
import dev.kodakiko.app.navigation.AppNavigator
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.data.secure.DeviceGate
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.LockControl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

@DependencyGraph(AppScope::class, bindingContainers = [StorageBindings::class, ServiceBindings::class, ViewModelBindings::class])
interface AppGraph : ViewModelGraph {
    val authService: AuthService

    val accountState: Observable<AccountState>

    val lockControl: LockControl

    val legacyAdoption: LegacyAdoption

    val deviceGate: DeviceGate

    val navigator: AppNavigator

    val startup: AppStartup

    val activityHolder: ActivityHolder

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
