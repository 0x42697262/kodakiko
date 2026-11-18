package dev.kodakiko.platform.picker

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

data class SharedFile(val file: File, val fileName: String, val mimeType: String)

object Sharing {
    fun chooser(context: Context, authority: String, files: List<SharedFile>, text: String?): Intent {
        require(files.isNotEmpty())
        val uris = files.map { FileProvider.getUriForFile(context, authority, it.file, it.fileName) }
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(files.single().mimeType).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(PickerConstants.ANY_MIME_TYPE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.clipData = clipData(files, uris)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (text != null) send.putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, null)
    }

    private fun clipData(files: List<SharedFile>, uris: List<Uri>): ClipData {
        val description = ClipDescription(files.first().fileName, files.map { it.mimeType }.distinct().toTypedArray())
        return ClipData(description, ClipData.Item(uris.first())).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    }
}
