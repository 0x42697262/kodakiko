package dev.kodakiko.app.di

import android.content.Context
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.capture.ActivityPermissions
import dev.kodakiko.app.capture.AppCameraPreview
import dev.kodakiko.app.capture.AppCaptureCamera
import dev.kodakiko.app.capture.AppCaptureFiles
import dev.kodakiko.app.capture.AppCaptureLocation
import dev.kodakiko.app.capture.AppCapturePermissions
import dev.kodakiko.app.capture.SystemCaptureClock
import dev.kodakiko.app.edit.EditAdapterConstants
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.platform.device.Haptics
import dev.kodakiko.platform.location.LocationProvider
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.ui.feature.capture.CameraPreview
import dev.kodakiko.ui.feature.capture.CaptureDeps
import dev.kodakiko.ui.feature.tags.TagsDeps
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher

@BindingContainer
object CaptureBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun activityPermissions(context: Context, holder: ActivityHolder): ActivityPermissions = ActivityPermissions(context, holder)

    @Provides
    @SingleIn(AppScope::class)
    fun captureCamera(tempFiles: TempFiles, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppCaptureCamera = AppCaptureCamera(tempFiles, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun cameraPreview(camera: AppCaptureCamera): CameraPreview = AppCameraPreview(camera)

    @Provides
    @SingleIn(AppScope::class)
    fun capturePermissions(permissions: ActivityPermissions): AppCapturePermissions = AppCapturePermissions(permissions)

    @Provides
    @SingleIn(AppScope::class)
    fun captureFiles(context: Context, tempFiles: TempFiles, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppCaptureFiles =
        AppCaptureFiles(context, tempFiles, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun captureLocation(context: Context, permissions: ActivityPermissions, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppCaptureLocation =
        AppCaptureLocation(LocationProvider(context), permissions, ioDispatcher)

    @Provides
    fun captureDeps(
        context: Context,
        tray: CaptureTray,
        imports: ImportPipeline,
        entries: EntriesService,
        index: IndexService,
        config: ConfigService,
        settings: AppDeviceSettings,
        camera: AppCaptureCamera,
        permissions: AppCapturePermissions,
        files: AppCaptureFiles,
        location: AppCaptureLocation,
        haptics: Haptics,
    ): CaptureDeps = CaptureDeps(
        tray = tray,
        imports = imports,
        entries = entries,
        index = index,
        config = config,
        settings = settings.settings,
        camera = camera,
        permissions = permissions,
        files = files,
        location = location,
        haptics = haptics::tick,
        clock = SystemCaptureClock,
        fileProviderAuthority = context.packageName + EditAdapterConstants.FILE_PROVIDER_SUFFIX,
    )

    @Provides
    fun tagsDeps(index: IndexService, entries: EntriesService, settings: AppDeviceSettings): TagsDeps = TagsDeps(index = index, entries = entries, settings = settings)
}
