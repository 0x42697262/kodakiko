package dev.kodakiko.platform.picker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

data class PickedFile(val file: File, val displayName: String?, val sizeBytes: Long, val mimeType: String?)

class UriCopier(context: Context) {
    private val resolver = context.contentResolver

    @WorkerThread
    fun copy(uri: Uri, destination: File): PickedFile? = try {
        PickedFile(destination, displayName(uri), copyBytes(uri, destination), resolver.getType(uri))
    } catch (_: IOException) {
        discard(destination)
    } catch (_: RuntimeException) {
        discard(destination)
    }

    private fun copyBytes(uri: Uri, destination: File): Long {
        val input = resolver.openInputStream(uri) ?: throw IOException(uri.toString())
        return input.use { source -> destination.outputStream().use { source.copyTo(it) } }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
        }

    private fun discard(destination: File): PickedFile? {
        destination.delete()
        return null
    }
}
