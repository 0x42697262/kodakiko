package dev.kodakiko.app.viewer

import android.net.Uri
import androidx.media3.datasource.DataSource
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.data.media.DecryptingDataSource
import dev.kodakiko.data.media.MediaUnavailableException
import dev.kodakiko.data.media.OriginalHandle
import dev.kodakiko.data.media.StreamFileReader
import dev.kodakiko.ui.feature.viewer.PreparedVideo
import dev.kodakiko.ui.feature.viewer.VideoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class AppVideoSource(private val media: AccountMedia, private val ioDispatcher: CoroutineDispatcher) : VideoSource {
    private val active: MutableSet<DecryptedVideo> = Collections.synchronizedSet(LinkedHashSet())

    override suspend fun prepare(entryId: String, mediaId: String, onProgress: (Float) -> Unit): PreparedVideo {
        val video = withContext(ioDispatcher) {
            val first = media.openOriginal(entryId, mediaId) ?: throw MediaUnavailableException()
            first.reader.close()
            DecryptedVideo(mediaId, first.handle) {
                media.openOriginal(entryId, mediaId)?.let { opened ->
                    opened.handle.release()
                    opened.reader
                } ?: throw MediaUnavailableException()
            }
        }
        active += video
        onProgress(ViewerAdapterConstants.PREPARED)
        return video
    }

    fun releaseAll() {
        val videos = synchronized(active) { active.toList() }
        videos.forEach { it.release() }
    }

    private inner class DecryptedVideo(
        mediaId: String,
        private val handle: OriginalHandle,
        openReader: () -> StreamFileReader,
    ) : PreparedVideo {
        private val released = AtomicBoolean(false)

        override val uri: Uri = Uri.Builder().scheme(ViewerAdapterConstants.MEDIA_URI_SCHEME).authority(mediaId).build()

        override val dataSourceFactory: DataSource.Factory = DecryptingDataSource.Factory(openReader)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            active -= this
            handle.release()
        }
    }
}
