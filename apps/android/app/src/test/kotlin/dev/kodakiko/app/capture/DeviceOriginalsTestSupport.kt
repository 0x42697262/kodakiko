package dev.kodakiko.app.capture

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogLevel
import java.io.File

class FakeMediaProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>("IMG_${uri.lastPathSegment}", BYTES.size))
        }

    override fun getType(uri: Uri): String? = TYPES[uri.lastPathSegment]

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File.createTempFile("media", ".bin").apply {
            writeBytes(BYTES)
            deleteOnExit()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val clip = extras?.getParcelable(CLIP_DATA, ClipData::class.java)
        calls += method to (0 until (clip?.itemCount ?: 0)).map { clip!!.getItemAt(it).uri }
        val request = PendingIntent.getActivity(context, 0, Intent(DELETE_ACTION), PendingIntent.FLAG_IMMUTABLE)
        return Bundle().apply { putParcelable(RESULT, request) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val PHOTO_ID = "77"
        const val VIDEO_ID = "78"
        const val PDF_ID = "79"
        const val DELETE_ACTION = "dev.kodakiko.test.DELETE_REQUEST"
        const val CLIP_DATA = "clip_data"
        const val RESULT = "result"
        val calls = mutableListOf<Pair<String, List<Uri>>>()
        val BYTES = byteArrayOf(1, 2, 3, 4, 5)
        val TYPES = mapOf(PHOTO_ID to "image/jpeg", VIDEO_ID to "video/mp4", PDF_ID to "application/pdf")
    }
}

object DeviceUris {
    const val MEDIA = "media"
    const val LOCAL_PICKER = "com.android.providers.media.photopicker"
    const val CLOUD_PICKER = "com.google.android.apps.photos.cloudpicker"

    fun picker(id: String, provider: String = LOCAL_PICKER, path: String = "picker"): Uri = Uri.parse("content://$MEDIA/$path/0/$provider/media/$id")

    fun image(id: String): Uri = Uri.parse("content://$MEDIA/external/images/media/$id")

    fun video(id: String): Uri = Uri.parse("content://$MEDIA/external/video/media/$id")
}

data class LoggedEvent(val level: LogLevel, val service: String, val event: String, val fields: Map<String, Any?>)

class RecordingDebugLog : DebugLog {
    override val enabled: Boolean = true

    val events = mutableListOf<LoggedEvent>()

    override fun record(level: LogLevel, service: String, event: String, fields: Map<String, Any?>) {
        events += LoggedEvent(level, service, event, fields)
    }
}
