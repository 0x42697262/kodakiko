package dev.kodakiko.data.files

import android.content.Context
import dev.kodakiko.contract.ID_PATTERN
import java.io.File

class AccountPaths(context: Context, val accountHash: String) {
    init {
        require(FilesConstants.ACCOUNT_HASH.matches(accountHash)) { FilesText.invalidAccountHash(accountHash) }
    }

    val root: File = File(File(context.filesDir, FilesConstants.APP_DATA_DIRECTORY), accountHash)
    val objectsDirectory: File = File(root, FilesConstants.OBJECTS_DIRECTORY)
    val plainThumbnailsDirectory: File = File(root, FilesConstants.PLAIN_THUMBNAILS_DIRECTORY)
    val tempDirectory: File = File(File(File(context.cacheDir, FilesConstants.APP_DATA_DIRECTORY), accountHash), FilesConstants.TMP_DIRECTORY)
    val databaseFile: File =
        File(File(context.filesDir, FilesConstants.DATABASE_DIRECTORY), FilesConstants.DATABASE_FILE_PREFIX + accountHash + FilesConstants.DATABASE_FILE_EXTENSION)

    fun objectFile(objectId: String): File = File(objectsDirectory, requireId(objectId) + FilesConstants.OBJECT_EXTENSION)

    fun partialFile(objectId: String): File =
        File(objectsDirectory, requireId(objectId) + FilesConstants.OBJECT_EXTENSION + FilesConstants.PARTIAL_EXTENSION)

    fun plainThumbnailFile(mediaId: String): File = File(plainThumbnailsDirectory, requireId(mediaId) + FilesConstants.PLAIN_THUMBNAIL_EXTENSION)

    private fun requireId(id: String): String {
        require(ID.matches(id)) { FilesText.INVALID_OBJECT_ID }
        return id
    }

    private companion object {
        val ID = Regex(ID_PATTERN)
    }
}
