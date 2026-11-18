package dev.kodakiko.app.capture

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi

object DeviceMediaUris {
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun mediaStoreUri(context: Context, uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return try {
            mapped(context, uri)
        } catch (_: RuntimeException) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun mapped(context: Context, uri: Uri): Uri? {
        val segments = uri.pathSegments
        return when {
            uri.authority == MediaStore.AUTHORITY && segments.firstOrNull() in CaptureAdapterConstants.PICKER_PATHS -> pickerUri(context, uri)
            uri.authority == MediaStore.AUTHORITY -> uri.takeIf { segments.lastOrNull()?.toLongOrNull() != null }
            DocumentsContract.isDocumentUri(context, uri) -> MediaStore.getMediaUri(context, uri)
            else -> null
        }
    }

    private fun pickerUri(context: Context, uri: Uri): Uri? {
        val segments = uri.pathSegments
        val provider = segments.getOrNull(CaptureAdapterConstants.PICKER_PROVIDER_INDEX)
        if (provider != CaptureAdapterConstants.LOCAL_PICKER_PROVIDER) return null
        val id = segments.lastOrNull()?.toLongOrNull() ?: return null
        val type = context.contentResolver.getType(uri) ?: return null
        val collection = when {
            type.startsWith(CaptureAdapterConstants.IMAGE_MIME_PREFIX) -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            type.startsWith(CaptureAdapterConstants.VIDEO_MIME_PREFIX) -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        return ContentUris.withAppendedId(collection, id)
    }
}
