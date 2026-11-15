package dev.kodakiko.data.media

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.key.Keyer
import coil3.request.ImageResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer

internal class ThumbnailFetcher(private val thumbnails: ThumbnailCache, private val request: ThumbnailImageRequest) : Fetcher {
    override suspend fun fetch(): FetchResult = when (val image = thumbnails.get(request.entryId, request.mediaId)) {
        is ThumbnailImage.PlainFile -> SourceFetchResult(ImageSource(image.file.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
        is ThumbnailImage.Bytes -> SourceFetchResult(ImageSource(Buffer().write(image.bytes), NoWriteFileSystem), null, DataSource.MEMORY)
        null -> throw MediaUnavailableException()
    }

    class Factory(private val thumbnails: ThumbnailCache) : Fetcher.Factory<ThumbnailImageRequest> {
        override fun create(data: ThumbnailImageRequest, options: Options, imageLoader: ImageLoader): Fetcher = ThumbnailFetcher(thumbnails, data)
    }
}

internal class OriginalFetcher(private val openOriginal: (OriginalImageRequest) -> OpenedOriginal?, private val request: OriginalImageRequest) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val original = openOriginal(request) ?: throw MediaUnavailableException()
        try {
            val source = DecryptedImageSource(original).buffer()
            return SourceFetchResult(ImageSource(source, NoWriteFileSystem), original.mimeType, DataSource.DISK)
        } catch (failure: Throwable) {
            original.close()
            throw failure
        }
    }

    class Factory(private val openOriginal: (OriginalImageRequest) -> OpenedOriginal?) : Fetcher.Factory<OriginalImageRequest> {
        override fun create(data: OriginalImageRequest, options: Options, imageLoader: ImageLoader): Fetcher = OriginalFetcher(openOriginal, data)
    }
}

internal object ThumbnailKeyer : Keyer<ThumbnailImageRequest> {
    override fun key(data: ThumbnailImageRequest, options: Options): String = MediaKeys.THUMBNAIL_IMAGE_KEY_PREFIX + data.mediaId
}

internal object OriginalKeyer : Keyer<OriginalImageRequest> {
    override fun key(data: OriginalImageRequest, options: Options): String = MediaKeys.ORIGINAL_IMAGE_KEY_PREFIX + data.mediaId
}

internal object OwnRequestsOnly : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data !is ThumbnailImageRequest && data !is OriginalImageRequest) {
            throw IllegalArgumentException(MediaText.unsupportedImageData(data::class.simpleName))
        }
        return chain.proceed()
    }
}
