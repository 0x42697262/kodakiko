package dev.kodakiko.data.files

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileStore private constructor(val paths: AccountPaths) {
    fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long {
        val partial = paths.partialFile(objectId)
        try {
            var total = 0L
            FileOutputStream(partial).use { output ->
                for (chunk in chunks) {
                    output.write(chunk)
                    total += chunk.size
                }
                output.fd.sync()
            }
            Files.move(partial.toPath(), paths.objectFile(objectId).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return total
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    fun readRange(objectId: String, offset: Long, length: Int): ByteArray =
        RandomAccessFile(paths.objectFile(objectId), FilesConstants.READ_ONLY_MODE).use { file ->
            val available = (file.length() - offset).coerceIn(0L, length.toLong()).toInt()
            val bytes = ByteArray(available)
            file.seek(offset)
            file.readFully(bytes)
            bytes
        }

    fun size(objectId: String): Long? = paths.objectFile(objectId).takeIf { it.isFile }?.length()

    fun delete(objectId: String) {
        paths.objectFile(objectId).delete()
    }

    fun listSizes(): Map<String, Long> =
        files(paths.objectsDirectory)
            .filter { it.name.endsWith(FilesConstants.OBJECT_EXTENSION) }
            .associate { it.name.removeSuffix(FilesConstants.OBJECT_EXTENSION) to it.length() }

    fun plainThumbnailFile(mediaId: String): File? = paths.plainThumbnailFile(mediaId).takeIf { it.isFile }

    fun writePlainThumbnail(mediaId: String, bytes: ByteArray) {
        paths.plainThumbnailFile(mediaId).writeBytes(bytes)
    }

    fun deletePlainThumbnail(mediaId: String) {
        paths.plainThumbnailFile(mediaId).delete()
    }

    fun sweepOrphans(knownObjectIds: Set<String>, olderThanMs: Long, knownMediaIds: Set<String>? = null) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val isStale = { file: File -> file.lastModified().let { it != 0L && it < cutoff } }
        for (file in files(paths.objectsDirectory)) {
            if (!file.name.endsWith(FilesConstants.OBJECT_EXTENSION)) continue
            if (file.name.removeSuffix(FilesConstants.OBJECT_EXTENSION) !in knownObjectIds && isStale(file)) file.delete()
        }
        if (knownMediaIds == null) return
        for (file in files(paths.plainThumbnailsDirectory)) {
            if (!file.name.endsWith(FilesConstants.PLAIN_THUMBNAIL_EXTENSION)) continue
            if (file.name.removeSuffix(FilesConstants.PLAIN_THUMBNAIL_EXTENSION) !in knownMediaIds && isStale(file)) file.delete()
        }
    }

    fun destroy() {
        paths.root.deleteRecursively()
    }

    companion object {
        fun open(context: Context, accountHash: String): FileStore {
            val paths = AccountPaths(context, accountHash)
            ensureDirectory(paths.objectsDirectory)
            ensureDirectory(paths.plainThumbnailsDirectory)
            for (file in files(paths.objectsDirectory)) {
                if (file.name.endsWith(FilesConstants.PARTIAL_EXTENSION)) file.delete()
            }
            return FileStore(paths)
        }

        internal fun ensureDirectory(directory: File) {
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException(FilesText.cannotCreateDirectory(directory.path))
        }

        private fun files(directory: File): List<File> = directory.listFiles()?.filter { it.isFile }.orEmpty()
    }
}
