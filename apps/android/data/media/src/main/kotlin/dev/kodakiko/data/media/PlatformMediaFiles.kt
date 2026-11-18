package dev.kodakiko.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import dev.kodakiko.media.MediaSize
import java.io.File
import java.io.FileInputStream

internal data class PhotoOrientation(val degrees: Int, val mirrored: Boolean) {
    val swapsAxes: Boolean get() = degrees in MediaConstants.AXIS_SWAPPING_DEGREES

    val isIdentity: Boolean get() = degrees == 0 && !mirrored

    fun upright(size: MediaSize): MediaSize = if (swapsAxes) MediaSize(size.height, size.width) else size

    fun apply(bitmap: Bitmap): Bitmap {
        if (isIdentity) return bitmap
        val matrix = Matrix().apply {
            setRotate(degrees.toFloat())
            if (mirrored) postScale(MediaConstants.MIRROR_SCALE, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycleIfReplaced(it) }
    }
}

internal fun readPhotoOrientation(file: File): PhotoOrientation {
    val tag = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return MediaConstants.EXIF_ORIENTATIONS[tag] ?: MediaConstants.UPRIGHT
}

internal fun decodePhotoBounds(file: File): MediaSize? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) MediaSize(options.outWidth, options.outHeight) else null
}

internal fun Bitmap.recycleIfReplaced(replacement: Bitmap) {
    if (replacement !== this) recycle()
}

internal inline fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T): T {
    val retriever = MediaMetadataRetriever()
    try {
        FileInputStream(file).use { retriever.setDataSource(it.fd) }
        return block(retriever)
    } finally {
        retriever.release()
    }
}
