package dev.kodakiko.service.entries

import dev.kodakiko.data.files.FileStore

interface EntryFiles {
    fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long

    fun delete(objectId: String)

    fun writePlainThumbnail(mediaId: String, bytes: ByteArray)

    fun deletePlainThumbnail(mediaId: String)
}

class FileStoreEntryFiles(private val store: () -> FileStore) : EntryFiles {
    override fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long = store().writeObject(objectId, chunks)

    override fun delete(objectId: String) {
        store().delete(objectId)
    }

    override fun writePlainThumbnail(mediaId: String, bytes: ByteArray) {
        store().writePlainThumbnail(mediaId, bytes)
    }

    override fun deletePlainThumbnail(mediaId: String) {
        store().deletePlainThumbnail(mediaId)
    }
}

internal fun EntryFiles.deleteQuietly(action: EntryFiles.() -> Unit) {
    try {
        action()
    } catch (_: Exception) {
        return
    }
}

internal fun EntryFiles.deleteMediaFilesQuietly(mediaId: String, originalObjectId: String, thumbnailObjectId: String) {
    deleteQuietly { delete(originalObjectId) }
    deleteQuietly { delete(thumbnailObjectId) }
    deleteQuietly { deletePlainThumbnail(mediaId) }
}
