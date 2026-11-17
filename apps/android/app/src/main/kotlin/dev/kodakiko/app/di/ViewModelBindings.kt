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
import dev.kodakiko.ui.feature.calendar.CalendarDeps
import dev.kodakiko.ui.feature.calendar.CalendarViewModel
import dev.kodakiko.ui.feature.capture.CaptureDeps
import dev.kodakiko.ui.feature.capture.CaptureViewModel
import dev.kodakiko.ui.feature.capture.CaptureViewModelFactory
import dev.kodakiko.ui.feature.edit.CropDeps
import dev.kodakiko.ui.feature.edit.CropViewModel
import dev.kodakiko.ui.feature.edit.CropViewModelFactory
import dev.kodakiko.ui.feature.edit.EditViewModel
import dev.kodakiko.ui.feature.edit.EditViewModelFactory
import dev.kodakiko.ui.feature.edit.EntryNewDeps
import dev.kodakiko.ui.feature.edit.EntryNewViewModel
import dev.kodakiko.ui.feature.gallery.DayViewModel
import dev.kodakiko.ui.feature.gallery.DayViewModelFactory
import dev.kodakiko.ui.feature.gallery.GalleryDeps
import dev.kodakiko.ui.feature.gallery.GalleryViewModel
import dev.kodakiko.ui.feature.gallery.TrashViewModel
import dev.kodakiko.ui.feature.lock.LockViewModel
import dev.kodakiko.ui.feature.lock.PendingChanges
import dev.kodakiko.ui.feature.tags.TagsDeps
import dev.kodakiko.ui.feature.tags.TagsViewModel
import dev.kodakiko.ui.feature.viewer.EntryDetailsDeps
import dev.kodakiko.ui.feature.viewer.EntryDetailsViewModel
import dev.kodakiko.ui.feature.viewer.EntryDetailsViewModelFactory
import dev.kodakiko.ui.feature.viewer.EntryViewerViewModel
import dev.kodakiko.ui.feature.viewer.EntryViewerViewModelFactory
import dev.kodakiko.ui.feature.viewer.VideoPlayerDeps
import dev.kodakiko.ui.feature.viewer.VideoPlayerViewModel
import dev.kodakiko.ui.feature.viewer.VideoPlayerViewModelFactory
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.map
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
    fun recoveryKey(auth: AuthService, random: RandomSource, accountState: Observable<AccountState>): ViewModel =
        RecoveryKeyViewModel(auth = auth, random = random, unlocked = accountState.flow.map { it is AccountState.Unlocked })

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
    @ViewModelKey(GalleryViewModel::class)
    fun gallery(deps: GalleryDeps): ViewModel = GalleryViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(TrashViewModel::class)
    fun trash(deps: GalleryDeps): ViewModel = TrashViewModel(deps)

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(DayViewModelFactory::class)
    fun day(deps: GalleryDeps): ManualViewModelAssistedFactory = DayViewModelFactory { date -> DayViewModel(deps, date) }

    @Provides
    @IntoMap
    @ViewModelKey(CalendarViewModel::class)
    fun calendar(deps: CalendarDeps): ViewModel = CalendarViewModel(deps)

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(EntryViewerViewModelFactory::class)
    fun entryViewer(deps: ViewerDepsFactory): ManualViewModelAssistedFactory =
        EntryViewerViewModelFactory { entryId -> EntryViewerViewModel(deps.create(), entryId) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(EntryDetailsViewModelFactory::class)
    fun entryDetails(deps: EntryDetailsDeps): ManualViewModelAssistedFactory =
        EntryDetailsViewModelFactory { entryId -> EntryDetailsViewModel(deps, entryId) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(VideoPlayerViewModelFactory::class)
    fun videoPlayer(deps: VideoPlayerDeps): ManualViewModelAssistedFactory =
        VideoPlayerViewModelFactory { entryId, mediaId -> VideoPlayerViewModel(deps, entryId, mediaId) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(EditViewModelFactory::class)
    fun edit(deps: EditDepsFactory): ManualViewModelAssistedFactory =
        EditViewModelFactory { entryId, isNew -> EditViewModel(deps.create(), entryId, isNew) }

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(CropViewModelFactory::class)
    fun crop(deps: CropDeps): ManualViewModelAssistedFactory = CropViewModelFactory { entryId, mediaId -> CropViewModel(deps, entryId, mediaId) }

    @Provides
    @IntoMap
    @ViewModelKey(EntryNewViewModel::class)
    fun entryNew(deps: EntryNewDeps): ViewModel = EntryNewViewModel(deps)

    @Provides
    @IntoMap
    @ManualViewModelAssistedFactoryKey(CaptureViewModelFactory::class)
    fun capture(deps: CaptureDeps): ManualViewModelAssistedFactory = CaptureViewModelFactory { entryId, mode -> CaptureViewModel(deps, entryId, mode) }

    @Provides
    @IntoMap
    @ViewModelKey(TagsViewModel::class)
    fun tags(deps: TagsDeps): ViewModel = TagsViewModel(deps)

    @Provides
    @IntoMap
    @ViewModelKey(PlaceholderViewModel::class)
    fun placeholder(auth: AuthService, accountState: Observable<AccountState>): ViewModel =
        PlaceholderViewModel(auth = auth, accountState = accountState)
}
