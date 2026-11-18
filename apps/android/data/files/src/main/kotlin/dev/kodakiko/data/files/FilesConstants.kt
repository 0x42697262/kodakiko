package dev.kodakiko.data.files

internal object FilesConstants {
    const val APP_DATA_DIRECTORY = "kodakiko"
    const val OBJECTS_DIRECTORY = "objects"
    const val OBJECT_EXTENSION = ".kdks"
    const val PARTIAL_EXTENSION = ".partial"
    const val PLAIN_THUMBNAILS_DIRECTORY = "thumbs-plain"
    const val PLAIN_THUMBNAIL_EXTENSION = ".webp"
    const val TMP_DIRECTORY = "tmp"
    const val TEMP_FILE_SEPARATOR = "-"
    const val EXTENSION_SEPARATOR = "."
    const val DATABASE_DIRECTORY = "SQLite"
    const val DATABASE_FILE_PREFIX = "kodakiko-"
    const val DATABASE_FILE_EXTENSION = ".db"
    const val ACCOUNT_HASH_HEX_CHARS = 16
    const val RANDOM_ID_BYTES = 16
    const val READ_ONLY_MODE = "r"
    val ACCOUNT_HASH = Regex("^[0-9a-f]{16}$")
    val EXTENSION = Regex("^[A-Za-z0-9]+$")
}

internal object FilesText {
    const val INVALID_OBJECT_ID = "The object id is not valid."
    const val NO_ACCOUNT = "No account is open for temporary files."

    fun invalidAccountHash(hash: String): String = "Not an account hash: $hash"

    fun invalidExtension(extension: String): String = "Not a file extension: $extension"

    fun cannotCreateDirectory(path: String): String = "Cannot create the directory $path"
}
