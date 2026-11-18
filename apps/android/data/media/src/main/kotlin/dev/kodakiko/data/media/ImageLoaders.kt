package dev.kodakiko.data.media

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.serviceLoaderEnabled

object ImageLoaders {
    fun create(context: Context, thumbnails: ThumbnailCache, openOriginal: (OriginalImageRequest) -> OpenedOriginal?): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache(null)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context).build() }
            .fileSystem(NoWriteFileSystem)
            .serviceLoaderEnabled(false)
            .components {
                add(OwnRequestsOnly)
                add(ThumbnailKeyer)
                add(OriginalKeyer)
                add(ThumbnailFetcher.Factory(thumbnails))
                add(OriginalFetcher.Factory(openOriginal))
            }
            .build()

    fun clearMemory(loader: ImageLoader) {
        loader.memoryCache?.clear()
    }
}
