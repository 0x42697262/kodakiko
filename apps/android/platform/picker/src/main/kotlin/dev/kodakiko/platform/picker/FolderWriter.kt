package dev.kodakiko.platform.picker

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class FolderWriter(context: Context, treeUri: Uri) {
    private val resolver: ContentResolver = context.contentResolver
    private val folder: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)
    private val created = ConcurrentHashMap<String, Uri>()

    @WorkerThread
    fun canWrite(): Boolean = folder?.canWrite() == true

    @WorkerThread
    fun createFile(name: String, mimeType: String): OutputStream? {
        val document = folder?.createFile(mimeType, name) ?: return null
        created[name] = document.uri
        return try {
            resolver.openOutputStream(document.uri)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    @WorkerThread
    fun delete(name: String): Boolean {
        val uri = created.remove(name) ?: folder?.findFile(name)?.uri ?: return false
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: FileNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

object FolderPermissions {
    private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun take(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(treeUri, READ_WRITE)
        true
    } catch (_: SecurityException) {
        false
    }

    fun release(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.releasePersistableUriPermission(treeUri, READ_WRITE)
        true
    } catch (_: SecurityException) {
        false
    }

    fun isHeld(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
}
