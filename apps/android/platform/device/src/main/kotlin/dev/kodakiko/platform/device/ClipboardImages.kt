package dev.kodakiko.platform.device

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

class ClipboardImages(context: Context) {
    private val appContext = context.applicationContext
    private val clipboard: ClipboardManager = appContext.getSystemService(ClipboardManager::class.java)

    fun hasImage(): Boolean = runCatching {
        clipboard.primaryClipDescription?.hasMimeType(DeviceConstants.IMAGE_MIME_TYPES) == true
    }.getOrDefault(false)

    fun readJpeg(): ByteArray? = runCatching {
        if (!hasImage()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(DeviceConstants.FIRST_CLIP_ITEM).uri ?: return null
        val bitmap = appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
        try {
            encodeJpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    private fun encodeJpeg(bitmap: Bitmap): ByteArray? {
        val output = ByteArrayOutputStream()
        val written = bitmap.compress(Bitmap.CompressFormat.JPEG, DeviceConstants.CLIPBOARD_JPEG_QUALITY, output)
        return if (written) output.toByteArray() else null
    }
}
