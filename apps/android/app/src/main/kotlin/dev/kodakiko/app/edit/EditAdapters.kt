package dev.kodakiko.app.edit

import android.content.Context
import android.content.Intent
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.app.viewer.AppViewerVoice
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.TempPurpose
import dev.kodakiko.platform.audio.AudioConstants
import dev.kodakiko.platform.audio.VoiceRecorder
import dev.kodakiko.platform.device.ClipboardImages
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.lock.UnlockedWork
import dev.kodakiko.service.shareout.ShareLauncher
import dev.kodakiko.service.shareout.ShareOutService
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.ui.feature.edit.EditClipboard
import dev.kodakiko.ui.feature.edit.EditClock
import dev.kodakiko.ui.feature.edit.EditImages
import dev.kodakiko.ui.feature.edit.EditRecorder
import dev.kodakiko.ui.feature.edit.EditSharing
import dev.kodakiko.ui.feature.edit.EditVoice
import dev.kodakiko.ui.feature.edit.RecordedVoiceNote
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.TimeZone

object SystemEditClock : EditClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun deviceOffsetMinutes(atMs: Long): Long = TimeZone.getDefault().getOffset(atMs) / EditAdapterConstants.MS_PER_MINUTE
}

class AppEditImages(private val thumbnails: GalleryThumbnails) : EditImages {
    override suspend fun thumbnail(entryId: String, mediaId: String) = thumbnails.load(entryId, mediaId)
}

class AppEditClipboard(
    context: Context,
    private val tempFiles: TempFiles,
    private val unlockedWork: UnlockedWork,
    private val ioDispatcher: CoroutineDispatcher,
) : EditClipboard {
    private val images by lazy { ClipboardImages(context) }

    override suspend fun hasImage(): Boolean = withContext(ioDispatcher) { images.hasImage() }

    override suspend fun readImage(): MediaInput? = unlockedWork.runUnlocked { withContext(ioDispatcher) { readOnIo() } }

    private fun readOnIo(): MediaInput? {
        val bytes = images.readJpeg() ?: return null
        val temp = tempFiles.create(TempPurpose.IMPORT, EditAdapterConstants.JPEG_EXTENSION)
        try {
            temp.use { it.write(bytes) }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        } finally {
            bytes.fill(0)
        }
        return MediaInput(MediaOrigin.PASTE, temp.file, null, null)
    }
}

class AppEditRecorder(context: Context, private val tempFiles: TempFiles, private val ioDispatcher: CoroutineDispatcher) : EditRecorder {
    private val recorder = VoiceRecorder(context)

    override suspend fun start() {
        withContext(ioDispatcher) {
            val temp = tempFiles.create(TempPurpose.IMPORT, AudioConstants.FILE_EXTENSION)
            temp.close()
            try {
                recorder.start(temp.file)
            } catch (failure: Exception) {
                temp.delete()
                throw failure
            }
        }
    }

    override suspend fun stop(): RecordedVoiceNote? = withContext(ioDispatcher) {
        recorder.stop()?.let { RecordedVoiceNote(MediaInput(MediaOrigin.CAMERA, it.file, null, null), it.durationMs, it.mimeType) }
    }

    override fun elapsedMs(): Long = recorder.elapsedMs()

    fun abandon() {
        recorder.stop()?.file?.delete()
    }
}

class AppEditVoice(private val voice: AppViewerVoice) : EditVoice {
    override val playing: StateFlow<String?> get() = voice.playing

    override suspend fun toggle(entryId: String, mediaId: String) = voice.toggle(entryId, mediaId)

    override fun stop() = voice.stop()
}

class AppEditSharing(private val shareOut: ShareOutService) : EditSharing {
    override suspend fun shareItem(entryId: String, mediaId: String) {
        share(entryId, mediaId)
    }

    override suspend fun saveToDevice(entryId: String, mediaId: String) {
        share(entryId, mediaId)
    }

    private suspend fun share(entryId: String, mediaId: String) {
        val items = shareOut.items(listOf(entryId)).filter { it.media.mediaId == mediaId }
        if (items.isNotEmpty()) shareOut.share(items, setOf(mediaId), ShareTextOption.NONE)
    }
}

class ActivityShareLauncher(private val context: Context, private val holder: ActivityHolder) : ShareLauncher {
    override fun launch(chooser: Intent) {
        val activity = holder.current
        if (activity != null) {
            activity.startActivity(chooser)
        } else {
            context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
