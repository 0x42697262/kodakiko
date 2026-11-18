package dev.kodakiko.app.capture

import android.net.Uri
import java.io.File

class PendingDeviceDeletes {
    private val lock = Any()
    private val picked = HashMap<String, Uri>()
    private val byMedia = LinkedHashMap<String, Uri>()

    fun picked(file: File, uri: Uri) {
        synchronized(lock) { picked[file.path] = uri }
    }

    fun imported(file: File, mediaId: String?) {
        synchronized(lock) {
            val uri = picked.remove(file.path) ?: return
            if (mediaId != null) byMedia[mediaId] = uri
        }
    }

    fun replaced(oldMediaId: String, newMediaId: String) {
        synchronized(lock) {
            val uri = byMedia.remove(oldMediaId) ?: return
            byMedia[newMediaId] = uri
        }
    }

    fun uris(mediaIds: Collection<String>): List<Uri> = synchronized(lock) { mediaIds.mapNotNull { byMedia[it] }.distinct() }

    fun forget(mediaIds: Collection<String>) {
        synchronized(lock) { mediaIds.forEach { byMedia.remove(it) } }
    }

    fun clear() {
        synchronized(lock) {
            picked.clear()
            byMedia.clear()
        }
    }
}
