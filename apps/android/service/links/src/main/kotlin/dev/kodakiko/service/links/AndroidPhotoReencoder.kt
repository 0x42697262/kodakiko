package dev.kodakiko.service.links

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import dev.kodakiko.media.MediaSize
import dev.kodakiko.media.fitWithin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class PhotoOrientation(val degrees: Int, val mirrored: Boolean) {
    val swapsAxes: Boolean get() = degrees in ReencoderConstants.AXIS_SWAPPING_DEGREES

    val isIdentity: Boolean get() = degrees == 0 && !mirrored

    fun upright(size: MediaSize): MediaSize = if (swapsAxes) MediaSize(size.height, size.width) else size

    fun apply(bitmap: Bitmap): Bitmap {
        if (isIdentity) return bitmap
        val matrix = Matrix().apply {
            setRotate(degrees.toFloat())
            if (mirrored) postScale(ReencoderConstants.MIRROR_SCALE, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycleIfReplaced(it) }
    }
}

private fun Bitmap.recycleIfReplaced(replacement: Bitmap) {
    if (replacement !== this) recycle()
}

class AndroidPhotoReencoder : PhotoReencoder {
    override suspend fun reencode(bytes: ByteArray, mimeType: String): ReencodedPhoto {
        val raw = decodeBounds(bytes) ?: throw IOException(LinksText.PHOTO_UNDECODABLE)
        val orientation = readOrientation(bytes)
        val upright = orientation.upright(raw)
        val fitted = fitWithin(upright.width, upright.height, LinksConstants.PHOTO_MAX_EDGE)
        val target = orientation.upright(fitted)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(raw, target) }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException(LinksText.PHOTO_UNDECODABLE)
        return encode(orientation.apply(sampled.scaledTo(target)))
    }

    private fun decodeBounds(bytes: ByteArray): MediaSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) MediaSize(options.outWidth, options.outHeight) else null
    }

    private fun readOrientation(bytes: ByteArray): PhotoOrientation {
        val tag = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return ReencoderConstants.EXIF_ORIENTATIONS[tag] ?: ReencoderConstants.UPRIGHT
    }

    private fun sampleSize(raw: MediaSize, target: MediaSize): Int {
        var size = 1
        while (raw.width / (size * ReencoderConstants.SAMPLE_SIZE_STEP) >= target.width &&
            raw.height / (size * ReencoderConstants.SAMPLE_SIZE_STEP) >= target.height
        ) {
            size *= ReencoderConstants.SAMPLE_SIZE_STEP
        }
        return size
    }

    private fun Bitmap.scaledTo(size: MediaSize): Bitmap {
        if (width == size.width && height == size.height) return this
        val scaled = try {
            Bitmap.createScaledBitmap(this, size.width, size.height, true)
        } catch (failure: RuntimeException) {
            recycle()
            throw failure
        }
        return scaled.also { recycleIfReplaced(it) }
    }

    private fun encode(bitmap: Bitmap): ReencodedPhoto =
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, LinksConstants.PHOTO_QUALITY, output)) throw IOException(LinksText.PHOTO_ENCODE_FAILED)
            ReencodedPhoto(output.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
}
