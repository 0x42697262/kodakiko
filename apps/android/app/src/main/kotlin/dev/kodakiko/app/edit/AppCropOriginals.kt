package dev.kodakiko.app.edit

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import dev.kodakiko.app.gallery.AccountMedia
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.TempPurpose
import dev.kodakiko.data.media.OriginalHandle
import dev.kodakiko.data.media.OriginalImageRequest
import dev.kodakiko.service.entries.CropRect
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.entries.toPixels
import dev.kodakiko.service.lock.UnlockedWork
import dev.kodakiko.ui.feature.edit.CropOriginal
import dev.kodakiko.ui.feature.edit.CropOriginals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AppCropOriginals(
    private val context: Context,
    private val media: AccountMedia,
    private val entries: EntriesService,
    private val tempFiles: TempFiles,
    private val unlockedWork: UnlockedWork,
    private val ioDispatcher: CoroutineDispatcher,
) : CropOriginals {
    override suspend fun open(entryId: String, mediaId: String): CropOriginal? = unlockedWork.runUnlocked { openUnlocked(entryId, mediaId) }

    private suspend fun openUnlocked(entryId: String, mediaId: String): CropOriginal? {
        val descriptor = entries.readMetadata(entryId).media.firstOrNull { it.mediaId == mediaId } ?: return null
        val handle = withContext(ioDispatcher) {
            media.openOriginal(entryId, mediaId)?.let { opened ->
                opened.reader.close()
                opened.handle
            }
        } ?: return null
        try {
            val preview = decode(entryId, descriptor, EditAdapterConstants.CROP_PREVIEW_MAX_PX) ?: run {
                handle.release()
                return null
            }
            return Opened(entryId, descriptor, handle, preview.asImageBitmap())
        } catch (failure: Exception) {
            handle.release()
            throw failure
        }
    }

    private suspend fun decode(entryId: String, descriptor: MediaDescriptorV1, maxPx: Int): Bitmap? {
        val loader = withContext(ioDispatcher) { media.current() }?.loader ?: return null
        val request = ImageRequest.Builder(context)
            .data(OriginalImageRequest(entryId, descriptor.mediaId, descriptor.mimeType))
            .size(maxPx)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = loader.execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    private inner class Opened(
        private val entryId: String,
        private val descriptor: MediaDescriptorV1,
        private val handle: OriginalHandle,
        override val bitmap: ImageBitmap,
    ) : CropOriginal {
        private val released = AtomicBoolean(false)

        override suspend fun crop(rect: CropRect): MediaInput = unlockedWork.runUnlocked { cropUnlocked(rect) }

        private suspend fun cropUnlocked(rect: CropRect): MediaInput {
            val full = decode(entryId, descriptor, EditAdapterConstants.CROP_MAX_PX) ?: throw IOException(descriptor.mediaId)
            return withContext(ioDispatcher) {
                val pixels = toPixels(rect, full.width, full.height)
                val cropped = Bitmap.createBitmap(full, pixels.originX, pixels.originY, pixels.width, pixels.height)
                val temp = tempFiles.create(TempPurpose.IMPORT, EditAdapterConstants.JPEG_EXTENSION)
                temp.close()
                try {
                    FileOutputStream(temp.file).use { output ->
                        if (!cropped.compress(Bitmap.CompressFormat.JPEG, EditAdapterConstants.CROP_JPEG_QUALITY, output)) throw IOException(temp.file.name)
                    }
                } catch (failure: Exception) {
                    temp.delete()
                    throw failure
                } finally {
                    if (cropped !== full) cropped.recycle()
                    full.recycle()
                }
                MediaInput(MediaOrigin.PICKER, temp.file, descriptor.originalFileName, null)
            }
        }

        override fun release() {
            if (released.compareAndSet(false, true)) handle.release()
        }
    }
}
