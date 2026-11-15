package dev.kodakiko.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import dev.kodakiko.media.MediaSize
import dev.kodakiko.media.fitWithin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ThumbnailEncoder(context: Context) {
    fun photo(file: File): ThumbnailResult {
        val raw = decodePhotoBounds(file) ?: throw IOException(MediaText.PHOTO_UNDECODABLE)
        val orientation = readPhotoOrientation(file)
        val upright = orientation.upright(raw).fitted()
        val target = orientation.upright(upright)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(raw, target) }
        val sampled = BitmapFactory.decodeFile(file.path, options) ?: throw IOException(MediaText.PHOTO_UNDECODABLE)
        return encode(orientation.apply(sampled.scaledTo(target)))
    }

    fun video(file: File, posterTimeMs: Long): ThumbnailResult {
        val frame = withRetriever(file) { retriever ->
            retriever.getFrameAtTime(posterTimeMs * MediaConstants.MICROSECONDS_PER_MILLISECOND, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: throw IOException(MediaText.VIDEO_FRAME_UNAVAILABLE)
        return encode(frame.scaledTo(MediaSize(frame.width, frame.height).fitted()))
    }

    private fun MediaSize.fitted(): MediaSize = fitWithin(width, height, MediaConstants.THUMBNAIL_MAX_EDGE)

    private fun sampleSize(raw: MediaSize, target: MediaSize): Int {
        var size = 1
        while (raw.width / (size * MediaConstants.SAMPLE_SIZE_STEP) >= target.width &&
            raw.height / (size * MediaConstants.SAMPLE_SIZE_STEP) >= target.height
        ) {
            size *= MediaConstants.SAMPLE_SIZE_STEP
        }
        return size
    }

    private fun Bitmap.scaledTo(size: MediaSize): Bitmap {
        val scaled = try {
            Bitmap.createScaledBitmap(this, size.width, size.height, true)
        } catch (failure: RuntimeException) {
            recycle()
            throw failure
        }
        return scaled.also { recycleIfReplaced(it) }
    }

    private fun encode(bitmap: Bitmap): ThumbnailResult =
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(webpFormat(), MediaConstants.THUMBNAIL_QUALITY, output)) throw IOException(MediaText.THUMBNAIL_ENCODE_FAILED)
            ThumbnailResult(output.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}
