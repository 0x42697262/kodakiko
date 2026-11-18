package dev.kodakiko.ui.feature.edit

import android.net.Uri
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.flow.StateFlow

interface EditClipboard {
    suspend fun hasImage(): Boolean

    suspend fun readImage(): MediaInput?

    fun copyText(text: String) {}
}

fun interface EditPicks {
    suspend fun copy(uri: Uri): MediaInput?

    fun imported(input: MediaInput, mediaId: String?) {}
}

interface DeviceOriginals {
    fun deletableCount(mediaIds: Collection<String>): Int

    fun replaced(oldMediaId: String, newMediaId: String)

    fun keep(mediaIds: Collection<String>)

    suspend fun delete(mediaIds: Collection<String>)

    fun forget(mediaIds: Collection<String>)
}

object NoDeviceOriginals : DeviceOriginals {
    override fun deletableCount(mediaIds: Collection<String>): Int = 0

    override fun replaced(oldMediaId: String, newMediaId: String) = Unit

    override fun keep(mediaIds: Collection<String>) = Unit

    override suspend fun delete(mediaIds: Collection<String>) = Unit

    override fun forget(mediaIds: Collection<String>) = Unit
}

data class RecordedVoiceNote(val input: MediaInput, val durationMs: Long, val mimeType: String)

interface EditRecorder {
    suspend fun start()

    suspend fun stop(): RecordedVoiceNote?

    fun elapsedMs(): Long
}

interface EditVoice {
    val playing: StateFlow<String?>

    suspend fun toggle(entryId: String, mediaId: String)

    fun stop()
}

interface EditSharing {
    suspend fun shareItem(entryId: String, mediaId: String)

    suspend fun saveToDevice(entryId: String, mediaId: String)
}

interface EditClock {
    fun nowMs(): Long

    fun deviceOffsetMinutes(atMs: Long): Long
}

interface EditImages {
    suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap?
}

object NoEditImages : EditImages {
    override suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap? = null
}

val LocalEditImages: ProvidableCompositionLocal<EditImages> = staticCompositionLocalOf { NoEditImages }

interface CropOriginal {
    val bitmap: ImageBitmap

    suspend fun crop(rect: CropRect): MediaInput

    fun release()
}

fun interface CropOriginals {
    suspend fun open(entryId: String, mediaId: String): CropOriginal?
}

class EditDeps(
    val entries: EntriesService,
    val imports: ImportPipeline,
    val index: IndexService,
    val config: ConfigService,
    val settings: StateFlow<DeviceSettings>,
    val clipboard: EditClipboard,
    val recorder: EditRecorder,
    val voice: EditVoice,
    val sharing: EditSharing,
    val notices: Observable<GalleryNotice?>,
    val flushes: EditFlushes,
    val clock: EditClock,
    val picks: EditPicks = EditPicks { null },
    val originals: DeviceOriginals = NoDeviceOriginals,
)

class CropDeps(
    val entries: EntriesService,
    val imports: ImportPipeline,
    val originals: CropOriginals,
    val config: ConfigService,
)

class EntryNewDeps(val entries: EntriesService, val trayIsEmpty: () -> Boolean)
