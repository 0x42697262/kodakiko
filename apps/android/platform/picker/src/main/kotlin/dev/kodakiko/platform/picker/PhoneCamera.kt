package dev.kodakiko.platform.picker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object PhoneCamera {
    fun photoIntent(context: Context, authority: String, output: File): Intent = captureIntent(MediaStore.ACTION_IMAGE_CAPTURE, context, authority, output)

    fun videoIntent(context: Context, authority: String, output: File): Intent = captureIntent(MediaStore.ACTION_VIDEO_CAPTURE, context, authority, output)

    private fun captureIntent(action: String, context: Context, authority: String, output: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority, output)
        return Intent(action)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(output.name, uri) }
    }
}
