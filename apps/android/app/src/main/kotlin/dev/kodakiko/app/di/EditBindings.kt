package dev.kodakiko.app.di

import android.content.Context
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.AppConstants
import dev.kodakiko.app.capture.AppCaptureFiles
import dev.kodakiko.app.edit.ActivityShareLauncher
import dev.kodakiko.app.edit.AppCropOriginals
import dev.kodakiko.app.edit.AppEditClipboard
import dev.kodakiko.app.edit.AppEditImages
import dev.kodakiko.app.edit.AppEditPicks
import dev.kodakiko.app.edit.AppEditRecorder
import dev.kodakiko.app.edit.AppEditSharing
import dev.kodakiko.app.edit.AppEditVoice
import dev.kodakiko.app.edit.EditAdapterConstants
import dev.kodakiko.app.edit.SystemEditClock
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.app.gallery.AppDeviceSettings
import dev.kodakiko.app.gallery.UnlockedImportPipeline
import dev.kodakiko.app.gallery.UnlockedShareOut
import dev.kodakiko.app.viewer.AppViewerVoice
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.DiskFileStore
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.DefaultImportPipeline
import dev.kodakiko.service.entries.DevicePlatformMedia
import dev.kodakiko.service.entries.DeviceSettingsSource
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.entries.FileMediaToolkit
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.ImportPipelineDeps
import dev.kodakiko.service.entries.SystemEntriesClock
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.UnlockedWork
import dev.kodakiko.service.shareout.DefaultShareOutService
import dev.kodakiko.service.shareout.FileStoreOriginalFiles
import dev.kodakiko.service.shareout.ShareOutDeps
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.feature.edit.CropDeps
import dev.kodakiko.ui.feature.edit.CropOriginals
import dev.kodakiko.ui.feature.edit.EditDeps
import dev.kodakiko.ui.feature.edit.EditFlushes
import dev.kodakiko.ui.feature.edit.EditImages
import dev.kodakiko.ui.feature.edit.EntryNewDeps
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

class EditDepsFactory(
    private val context: Context,
    private val entries: EntriesService,
    private val imports: ImportPipeline,
    private val index: IndexService,
    private val config: ConfigService,
    private val settings: AppDeviceSettings,
    private val clipboard: AppEditClipboard,
    private val recorder: AppEditRecorder,
    private val media: AccountMedia,
    private val sharing: AppEditSharing,
    private val notices: Observable<GalleryNotice?>,
    private val flushes: EditFlushes,
    private val picks: AppEditPicks,
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun create(): EditDeps = EditDeps(
        entries = entries,
        imports = imports,
        index = index,
        config = config,
        settings = settings.settings,
        clipboard = clipboard,
        recorder = recorder,
        voice = AppEditVoice(AppViewerVoice(context, media, ioDispatcher)),
        sharing = sharing,
        notices = notices,
        flushes = flushes,
        clock = SystemEditClock,
        picks = picks,
    )
}

@BindingContainer
object EditBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun editFlushes(): EditFlushes = EditFlushes()

    @Provides
    @SingleIn(AppScope::class)
    fun importPipeline(
        context: Context,
        sodium: Sodium,
        files: EntryFiles,
        config: ConfigService,
        settings: AppDeviceSettings,
        unlockedWork: UnlockedWork,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ImportPipeline = UnlockedImportPipeline(
        DefaultImportPipeline(
            ImportPipelineDeps(
                sodium = sodium,
                files = files,
                toolkit = FileMediaToolkit(DevicePlatformMedia(context)),
                clock = SystemEntriesClock,
                config = config::current,
                deviceSettings = object : DeviceSettingsSource {
                    override fun loaded(): Boolean = settings.loaded()

                    override fun get() = settings.settings.value
                },
                randomId = { encodeBytes(sodium.randomBytes(AppConstants.RANDOM_ID_BYTES)) },
                ioDispatcher = ioDispatcher,
            ),
        ),
        unlockedWork,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun shareOutService(
        context: Context,
        sodium: Sodium,
        rowStore: RowStorePort,
        fileStore: DiskFileStore,
        entryKeys: EntryKeys,
        entries: EntriesService,
        holder: ActivityHolder,
        unlockedWork: UnlockedWork,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ShareOutService = UnlockedShareOut(
        DefaultShareOutService(
            ShareOutDeps(
                context = context,
                authority = context.packageName + EditAdapterConstants.FILE_PROVIDER_SUFFIX,
                sodium = sodium,
                rowStore = rowStore,
                files = FileStoreOriginalFiles { fileStore.require() },
                entryKeys = entryKeys,
                readMetadata = entries::readMetadata,
                fetchOriginal = {},
                launcher = ActivityShareLauncher(context, holder),
                now = System::currentTimeMillis,
                scope = scope,
                ioDispatcher = ioDispatcher,
            ),
        ),
        unlockedWork,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun editClipboard(context: Context, tempFiles: TempFiles, unlockedWork: UnlockedWork, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppEditClipboard =
        AppEditClipboard(context, tempFiles, unlockedWork, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun editPicks(files: AppCaptureFiles, unlockedWork: UnlockedWork): AppEditPicks = AppEditPicks(files, unlockedWork)

    @Provides
    @SingleIn(AppScope::class)
    fun editRecorder(context: Context, tempFiles: TempFiles, @IoDispatcher ioDispatcher: CoroutineDispatcher): AppEditRecorder =
        AppEditRecorder(context, tempFiles, ioDispatcher)

    @Provides
    @SingleIn(AppScope::class)
    fun editSharing(shareOut: ShareOutService): AppEditSharing = AppEditSharing(shareOut)

    @Provides
    @SingleIn(AppScope::class)
    fun editImages(thumbnails: GalleryThumbnails): EditImages = AppEditImages(thumbnails)

    @Provides
    @SingleIn(AppScope::class)
    fun cropOriginals(
        context: Context,
        media: AccountMedia,
        entries: EntriesService,
        tempFiles: TempFiles,
        unlockedWork: UnlockedWork,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): CropOriginals = AppCropOriginals(context, media, entries, tempFiles, unlockedWork, ioDispatcher)

    @Provides
    fun editDepsFactory(
        context: Context,
        entries: EntriesService,
        imports: ImportPipeline,
        index: IndexService,
        config: ConfigService,
        settings: AppDeviceSettings,
        clipboard: AppEditClipboard,
        recorder: AppEditRecorder,
        media: AccountMedia,
        sharing: AppEditSharing,
        notices: Observable<GalleryNotice?>,
        flushes: EditFlushes,
        picks: AppEditPicks,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): EditDepsFactory = EditDepsFactory(context, entries, imports, index, config, settings, clipboard, recorder, media, sharing, notices, flushes, picks, ioDispatcher)

    @Provides
    fun cropDeps(entries: EntriesService, imports: ImportPipeline, originals: CropOriginals, config: ConfigService): CropDeps =
        CropDeps(entries = entries, imports = imports, originals = originals, config = config)

    @Provides
    fun entryNewDeps(entries: EntriesService, tray: CaptureTray): EntryNewDeps = EntryNewDeps(entries = entries, trayIsEmpty = { tray.items.isEmpty() })
}
