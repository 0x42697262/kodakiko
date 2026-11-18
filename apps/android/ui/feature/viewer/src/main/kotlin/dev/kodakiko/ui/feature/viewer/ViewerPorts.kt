package dev.kodakiko.ui.feature.viewer

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.media3.datasource.DataSource
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.stats.StatsService
import dev.kodakiko.ui.core.notice.GalleryNotice
import kotlinx.coroutines.flow.StateFlow

interface ViewerMedia {
    suspend fun isPinned(entryId: String): Boolean

    suspend fun setPinned(entryId: String, pinned: Boolean)

    suspend fun isOriginalOnDevice(mediaId: String): Boolean
}

interface PreparedVideo {
    val uri: Uri

    val dataSourceFactory: DataSource.Factory

    fun release()
}

fun interface VideoSource {
    suspend fun prepare(entryId: String, mediaId: String, onProgress: (Float) -> Unit): PreparedVideo
}

interface ViewerVoice {
    val playing: StateFlow<String?>

    suspend fun toggle(entryId: String, mediaId: String)

    fun stop()
}

interface ViewerClock {
    fun nowMs(): Long

    fun deviceOffsetMinutes(atMs: Long): Long
}

@Immutable
data class DownloadProgress(val received: Long, val total: Long)

sealed interface OriginalImage {
    @Immutable
    data class Loaded(val bitmap: ImageBitmap) : OriginalImage

    data object Missing : OriginalImage

    data object Failed : OriginalImage
}

interface ViewerImages {
    suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap?

    suspend fun original(entryId: String, mediaId: String, onProgress: (DownloadProgress) -> Unit): OriginalImage
}

object NoViewerImages : ViewerImages {
    override suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap? = null

    override suspend fun original(entryId: String, mediaId: String, onProgress: (DownloadProgress) -> Unit): OriginalImage = OriginalImage.Missing
}

val LocalViewerImages: ProvidableCompositionLocal<ViewerImages> = staticCompositionLocalOf { NoViewerImages }

interface VideoSurfaces {
    @Composable
    fun Inline(video: PreparedVideo, muted: Boolean, modifier: Modifier)

    @Composable
    fun FullScreen(video: PreparedVideo, onProblem: (VideoProblem) -> Unit, modifier: Modifier)
}

val LocalVideoSurfaces: ProvidableCompositionLocal<VideoSurfaces> = staticCompositionLocalOf { ExoVideoSurfaces }

class ViewerDeps(
    val index: IndexService,
    val entries: EntriesService,
    val media: ViewerMedia,
    val videos: VideoSource,
    val voice: ViewerVoice,
    val settings: StateFlow<DeviceSettings>,
    val wifi: StateFlow<Boolean>,
    val accountMode: StateFlow<AccountMode>,
    val config: ConfigService,
    val owners: suspend (String) -> String?,
    val notices: Observable<GalleryNotice?>,
    val gallery: (String) -> Unit,
    val clock: ViewerClock,
)

class EntryDetailsDeps(val stats: StatsService, val settings: StateFlow<DeviceSettings>)

class VideoPlayerDeps(val source: VideoSource)
