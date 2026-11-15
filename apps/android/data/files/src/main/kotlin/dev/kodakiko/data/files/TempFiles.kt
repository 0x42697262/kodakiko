package dev.kodakiko.data.files

import android.content.Context
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.Sodium
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

enum class TempPurpose(val wire: String) {
    IMPORT("import"),
    VIEW("view"),
    PLAY("play"),
}

class TempFile internal constructor(val file: File) : Closeable {
    private val output = FileOutputStream(file)
    private var closed = false

    fun write(bytes: ByteArray) {
        output.write(bytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }

    fun delete() {
        close()
        file.delete()
    }
}

class TempFiles(private val context: Context, private val sodium: Sodium, private val activeAccountHash: () -> String?) {
    fun create(purpose: TempPurpose, extension: String): TempFile {
        require(FilesConstants.EXTENSION.matches(extension)) { FilesText.invalidExtension(extension) }
        val hash = checkNotNull(activeAccountHash()) { FilesText.NO_ACCOUNT }
        val directory = AccountPaths(context, hash).tempDirectory
        FileStore.ensureDirectory(directory)
        val name = purpose.wire + FilesConstants.TEMP_FILE_SEPARATOR + encodeBytes(sodium.randomBytes(FilesConstants.RANDOM_ID_BYTES)) +
            FilesConstants.EXTENSION_SEPARATOR + extension
        return TempFile(File(directory, name))
    }

    fun wipe() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun wipePurpose(purpose: TempPurpose) {
        val prefix = purpose.wire + FilesConstants.TEMP_FILE_SEPARATOR
        context.cacheDir.walkTopDown().filter { it.isFile && it.name.startsWith(prefix) }.toList().forEach { it.delete() }
    }

    fun list(): List<File> = context.cacheDir.walkTopDown().drop(1).toList()
}
