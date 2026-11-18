package dev.kodakiko.app.viewer

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.media.MediaUnavailableException
import dev.kodakiko.data.media.OriginalImageRequest
import dev.kodakiko.data.net.NetworkMonitor
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.ui.feature.calendar.CalendarClock
import dev.kodakiko.ui.feature.calendar.CalendarSync
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import dev.kodakiko.ui.feature.viewer.DownloadProgress
import dev.kodakiko.ui.feature.viewer.OriginalImage
import dev.kodakiko.ui.feature.viewer.ViewerClock
import dev.kodakiko.ui.feature.viewer.ViewerImages
import dev.kodakiko.ui.feature.viewer.ViewerMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

object SystemCalendarClock : CalendarClock {
    override fun todayLocalDate(): String = LocalDate.now(ZoneId.systemDefault()).toString()
}

object UnwiredCalendarSync : CalendarSync {
    override suspend fun syncNow() = Unit
}

object SystemViewerClock : ViewerClock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun deviceOffsetMinutes(atMs: Long): Long = TimeZone.getDefault().getOffset(atMs) / ViewerAdapterConstants.MS_PER_MINUTE
}

class WifiState(monitor: NetworkMonitor) {
    private val state = MutableStateFlow(monitor.isWifi())

    val wifi: StateFlow<Boolean> = state.asStateFlow()

    init {
        monitor.subscribe { network -> state.value = network.unmetered }
    }
}

class CoilViewerImages(
    private val context: Context,
    private val media: AccountMedia,
    private val thumbnails: GalleryThumbnails,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewerImages {
    override suspend fun thumbnail(entryId: String, mediaId: String): ImageBitmap? = thumbnails.load(entryId, mediaId)

    override suspend fun original(entryId: String, mediaId: String, onProgress: (DownloadProgress) -> Unit): OriginalImage {
        val opened = withContext(ioDispatcher) { media.current() } ?: return OriginalImage.Missing
        val request = ImageRequest.Builder(context)
            .data(OriginalImageRequest(entryId, mediaId, null))
            .size(ViewerAdapterConstants.ORIGINAL_MAX_PX)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .build()
        return when (val result = opened.loader.execute(request)) {
            is SuccessResult -> OriginalImage.Loaded(result.image.toBitmap().asImageBitmap())
            is ErrorResult -> if (result.throwable is MediaUnavailableException) OriginalImage.Missing else OriginalImage.Failed
        }
    }
}

class AppViewerMedia(private val media: AccountMedia, private val ioDispatcher: CoroutineDispatcher) : ViewerMedia {
    override suspend fun isPinned(entryId: String): Boolean = withContext(ioDispatcher) { media.current()?.originals?.isPinned(entryId) == true }

    override suspend fun setPinned(entryId: String, pinned: Boolean) {
        withContext(ioDispatcher) {
            val originals = media.current()?.originals ?: throw MediaUnavailableException()
            if (pinned) originals.pinEntry(entryId) else originals.unpinEntry(entryId)
        }
    }

    override suspend fun isOriginalOnDevice(mediaId: String): Boolean = withContext(ioDispatcher) {
        val opened = media.current() ?: return@withContext false
        val row = opened.database.transaction { tx -> tx.objects.byMedia(mediaId).firstOrNull { it.variant == ObjectVariant.ORIGINAL } }
        row != null && row.localState == ObjectLocalState.PRESENT && opened.files.size(row.id) != null
    }
}

class EntryOwners(private val rowStore: RowStorePort, private val ioDispatcher: CoroutineDispatcher) {
    suspend fun ownerOf(entryId: String): String? = withContext(ioDispatcher) {
        rowStore.transaction { tx ->
            tx.memberships.byEntry(entryId)
                .firstNotNullOfOrNull { membership -> tx.collections.get(membership.collectionId)?.takeIf { it.role == CollectionRole.VIEWER } }
                ?.ownerUsername
        }
    }
}
