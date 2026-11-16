package dev.kodakiko.app.di

import androidx.lifecycle.ViewModel
import dev.kodakiko.app.navigation.PlaceholderViewModel
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.ui.feature.auth.CancelDeletionViewModel
import dev.kodakiko.ui.feature.auth.ConnectServerViewModel
import dev.kodakiko.ui.feature.auth.DeletionDueAt
import dev.kodakiko.ui.feature.auth.LocalSetupViewModel
import dev.kodakiko.ui.feature.auth.RecoverViewModel
import dev.kodakiko.ui.feature.auth.RecoveryKeyViewModel
import dev.kodakiko.ui.feature.auth.SignInViewModel
import dev.kodakiko.ui.feature.auth.SignUpViewModel
import dev.kodakiko.ui.feature.auth.WelcomeViewModel
import dev.kodakiko.ui.feature.lock.LockViewModel
import dev.kodakiko.ui.feature.lock.PendingChanges
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineDispatcher
import java.time.ZoneId

@BindingContainer
object ViewModelBindings {
    @Provides
    fun metroViewModelFactory(factory: AppViewModelFactory): MetroViewModelFactory = factory

    @Provides
    @IntoMap
    @ViewModelKey(WelcomeViewModel::class)
    fun welcome(auth: AuthService, @InviteToken inviteToken: Observable<String?>): ViewModel =
        WelcomeViewModel(auth = auth, inviteToken = inviteToken)

    @Provides
    @IntoMap
    @ViewModelKey(SignUpViewModel::class)
    fun signUp(auth: AuthService, config: ConfigService, @InviteToken inviteToken: Observable<String?>): ViewModel =
        SignUpViewModel(auth = auth, config = config, inviteToken = inviteToken)

    @Provides
    @IntoMap
    @ViewModelKey(RecoveryKeyViewModel::class)
    fun recoveryKey(auth: AuthService, random: RandomSource): ViewModel = RecoveryKeyViewModel(auth = auth, random = random)

    @Provides
    @IntoMap
    @ViewModelKey(SignInViewModel::class)
    fun signIn(auth: AuthService, accountState: Observable<AccountState>): ViewModel = SignInViewModel(auth = auth, accountState = accountState)

    @Provides
    @IntoMap
    @ViewModelKey(RecoverViewModel::class)
    fun recover(auth: AuthService, accountState: Observable<AccountState>): ViewModel = RecoverViewModel(auth = auth, accountState = accountState)

    @Provides
    @IntoMap
    @ViewModelKey(CancelDeletionViewModel::class)
    fun cancelDeletion(auth: AuthService, dueAt: DeletionDueAt): ViewModel =
        CancelDeletionViewModel(auth = auth, dueAt = dueAt, zone = ZoneId.systemDefault())

    @Provides
    @IntoMap
    @ViewModelKey(LocalSetupViewModel::class)
    fun localSetup(auth: AuthService): ViewModel = LocalSetupViewModel(auth = auth)

    @Provides
    @IntoMap
    @ViewModelKey(ConnectServerViewModel::class)
    fun connectServer(auth: AuthService, accountState: Observable<AccountState>): ViewModel =
        ConnectServerViewModel(auth = auth, accountState = accountState)

    @Provides
    @IntoMap
    @ViewModelKey(LockViewModel::class)
    fun lock(
        auth: AuthService,
        lockControl: LockControl,
        adoption: LegacyAdoption,
        pendingChanges: PendingChanges,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ViewModel = LockViewModel(auth = auth, lock = lockControl, adoption = adoption, pendingChanges = pendingChanges, ioDispatcher = ioDispatcher)

    @Provides
    @IntoMap
    @ViewModelKey(PlaceholderViewModel::class)
    fun placeholder(auth: AuthService, accountState: Observable<AccountState>): ViewModel =
        PlaceholderViewModel(auth = auth, accountState = accountState)
}
