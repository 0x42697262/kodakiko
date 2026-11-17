package dev.kodakiko.app.di

import android.app.Application
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.AppStartup
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.navigation.AppNavigator
import dev.kodakiko.app.navigation.HomeSettings
import dev.kodakiko.app.system.SystemUiSettings
import dev.kodakiko.data.log.DebugLogFile
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.data.secure.DeviceGate
import dev.kodakiko.log.DebugLog
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.service.lock.UnlockedWork
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.ui.feature.calendar.CalendarThumbnails
import dev.kodakiko.ui.feature.capture.CameraPreview
import dev.kodakiko.ui.feature.edit.EditImages
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import dev.kodakiko.ui.feature.links.LinkThumbnails
import dev.kodakiko.ui.feature.sharing.SharingThumbnails
import dev.kodakiko.ui.feature.viewer.ViewerImages
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import kotlinx.coroutines.flow.StateFlow

@DependencyGraph(AppScope::class, bindingContainers = [StorageBindings::class, ServiceBindings::class, GalleryBindings::class, ViewerBindings::class, EditBindings::class, CaptureBindings::class, SharingBindings::class, SyncBindings::class, SettingsBindings::class, MoreBindings::class, ViewModelBindings::class])
interface AppGraph : ViewModelGraph {
    val authService: AuthService

    val accountState: Observable<AccountState>

    val lockControl: LockControl

    val legacyAdoption: LegacyAdoption

    val deviceGate: DeviceGate

    val navigator: AppNavigator

    val startup: AppStartup

    val activityHolder: ActivityHolder

    val indexService: IndexService

    val galleryThumbnails: GalleryThumbnails

    val calendarThumbnails: CalendarThumbnails

    val viewerImages: ViewerImages

    val editImages: EditImages

    val captureTray: CaptureTray

    val cameraPreview: CameraPreview

    val sharingThumbnails: SharingThumbnails

    val linkThumbnails: LinkThumbnails

    val homeSettings: StateFlow<HomeSettings>

    val syncService: SyncService

    val unlockedWork: UnlockedWork

    val systemUiSettings: StateFlow<SystemUiSettings>

    val debugLog: DebugLog

    val deviceSettings: AppDeviceSettings

    val debugLogFile: DebugLogFile

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
