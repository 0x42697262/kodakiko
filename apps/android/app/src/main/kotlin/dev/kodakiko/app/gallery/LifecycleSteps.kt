package dev.kodakiko.app.gallery

import dev.kodakiko.app.edit.AppEditRecorder
import dev.kodakiko.app.edit.EditAdapterConstants
import dev.kodakiko.app.viewer.AppVideoSource
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.lock.LifecycleStep
import dev.kodakiko.service.lock.LockStepNames
import dev.kodakiko.ui.core.notice.GalleryNotice
import dev.kodakiko.ui.feature.edit.EditFlushes
import dev.kodakiko.ui.feature.gallery.GalleryFilterStore

class LifecycleSteps(
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
) {
    val lock: List<LifecycleStep> = listOf(
        unwired(LockStepNames.CANCEL_EXPORT),
        unwired(LockStepNames.STOP_SYNC),
        unwired(LockStepNames.CANCEL_DOWNLOADS),
        LifecycleStep(LockStepNames.RELEASE_VIDEO) { videos.releaseAll() },
        unwired(LockStepNames.RELEASE_ORIGINALS),
        LifecycleStep(LockStepNames.CLEAR_THUMBNAILS) { media.clear() },
        LifecycleStep(LockStepNames.CLEAR_TRAY) { tray.clear(true) },
        LifecycleStep(LockStepNames.WIPE_TEMP) { tempFiles.wipe() },
        LifecycleStep(LockStepNames.CLEAR_INDEX) { index.clear() },
        LifecycleStep(LockStepNames.CLEAR_UI_STATE) {
            filter.reset()
            notices.value = null
            settings.unload()
        },
        unwired(LockStepNames.CLEAR_COLLECTION_NAMES),
        unwired(LockStepNames.CLEAR_ACCOUNT),
        LifecycleStep(EditAdapterConstants.FLUSH_EDITS) {
            recorder.abandon()
            flushes.flushAll()
        },
        LifecycleStep(LockStepNames.WIPE_KEYS) { entryKeys.clear() },
    )

    val unlock: List<LifecycleStep> = listOf(
        LifecycleStep(LockStepNames.LOAD_SETTINGS) { settings.load() },
        unwired(LockStepNames.START_SYNC),
        LifecycleStep(LockStepNames.BUILD_INDEX) { index.build() },
        unwired(LockStepNames.SWEEP_ORPHANS),
        unwired(LockStepNames.ENFORCE_CACHE),
        unwired(LockStepNames.REFRESH_ME),
    )

    private fun unwired(name: String): LifecycleStep = LifecycleStep(name) {}
}
