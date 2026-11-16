package dev.kodakiko.app.gallery

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.kodakiko.data.media.ThumbnailImageRequest
import dev.kodakiko.ui.feature.gallery.GalleryThumbnails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class CoilGalleryThumbnails(
    private val context: Context,
    private val media: AccountMedia,
    private val ioDispatcher: CoroutineDispatcher,
) : GalleryThumbnails {
    override suspend fun load(entryId: String, mediaId: String): ImageBitmap? {
        val opened = withContext(ioDispatcher) { media.current() } ?: return null
        val request = ImageRequest.Builder(context).data(ThumbnailImageRequest(entryId, mediaId)).build()
        val result = opened.loader.execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap().asImageBitmap()
    }
}
