package dev.kodakiko.app.gallery

import dev.kodakiko.app.capture.PendingDeviceDeletes
import dev.kodakiko.app.edit.AppEditRecorder
import dev.kodakiko.app.edit.EditAdapterConstants
import dev.kodakiko.app.viewer.AppVideoSource
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.service.account.AccountService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.backup.ExportService
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.LifecycleStep
import dev.kodakiko.service.lock.LockStepNames
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.service.sharing.CollectionNames
import dev.kodakiko.service.sync.SyncService
import dev.kodakiko.service.sync.Uploader
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.feature.edit.EditFlushes
import dev.kodakiko.ui.feature.gallery.GalleryFilterStore

class LifecycleSteps(
    private val sync: LifecycleSync,
    private val index: IndexService,
    private val media: AccountMedia,
    private val tempFiles: TempFiles,
    private val filter: GalleryFilterStore,
    private val notices: Observable<GalleryNotice?>,
    private val settings: AppDeviceSettings,
    private val entryKeys: EntryKeys,
    private val videos: AppVideoSource,
    private val flushes: EditFlushes,
    private val recorder: AppEditRecorder,
    private val tray: CaptureTray,
    private val deviceDeletes: PendingDeviceDeletes,
    private val collectionNames: CollectionNames,
    private val accounts: AccountService,
    private val shareOut: Lazy<ShareOutService>,
) {
    val lock: List<LifecycleStep> = listOf(
        LifecycleStep(LockStepNames.CANCEL_EXPORT) { sync.exporter.cancel() },
        LifecycleStep(LockStepNames.STOP_SYNC) {
            sync.service.stop()
            sync.linkUploader.cancelAll()
        },
        LifecycleStep(LockStepNames.CANCEL_DOWNLOADS) { media.abortDownloads() },
        LifecycleStep(LockStepNames.RELEASE_VIDEO) { videos.releaseAll() },
        LifecycleStep(LockStepNames.RELEASE_ORIGINALS) { media.releaseOriginals() },
        LifecycleStep(LockStepNames.CLEAR_THUMBNAILS) { media.clear() },
        LifecycleStep(LockStepNames.CLEAR_TRAY) {
            deviceDeletes.clear()
            tray.clear(true)
        },
        LifecycleStep(LockStepNames.WIPE_TEMP) { tempFiles.wipe() },
        LifecycleStep(LockStepNames.CLEAR_INDEX) { index.clear() },
        LifecycleStep(LockStepNames.CLEAR_UI_STATE) {
            filter.reset()
            notices.value = null
            settings.unload()
        },
        LifecycleStep(LockStepNames.CLEAR_COLLECTION_NAMES) { collectionNames.clear() },
        LifecycleStep(LockStepNames.CLEAR_ACCOUNT) { accounts.clear() },
        LifecycleStep(EditAdapterConstants.FLUSH_EDITS) {
            recorder.abandon()
            flushes.flushAll()
        },
        LifecycleStep(LockStepNames.WIPE_KEYS) {
            entryKeys.clear()
            tempFiles.wipe()
            shareOut.value.releaseAll()
        },
    )

    val unlock: List<LifecycleStep> = listOf(
        LifecycleStep(LockStepNames.LOAD_SETTINGS) {
            tempFiles.wipe()
            settings.load()
        },
        LifecycleStep(LockStepNames.START_SYNC) { if (!sync.localOnly()) sync.service.start() },
        LifecycleStep(LockStepNames.BUILD_INDEX) { index.build() },
        LifecycleStep(LockStepNames.SWEEP_ORPHANS) { media.sweepOrphans() },
        LifecycleStep(LockStepNames.ENFORCE_CACHE) { media.enforceCache() },
        LifecycleStep(LockStepNames.REFRESH_ME) { if (!sync.localOnly()) accounts.refreshMe() },
    )

    class LifecycleSync(
        private val sync: Lazy<SyncService>,
        private val links: Lazy<Uploader>,
        private val export: Lazy<ExportService>,
        val localOnly: () -> Boolean,
    ) {
        val service: SyncService get() = sync.value

        val linkUploader: Uploader get() = links.value

        val exporter: ExportService get() = export.value
    }
}
