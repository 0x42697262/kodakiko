package dev.kodakiko.service.backup

import dev.kodakiko.exporting.EXPORT_PART_MAX_BYTES
import java.io.IOException
import java.io.OutputStream

class SafExportDestination(
    override val partMaxBytes: Long = EXPORT_PART_MAX_BYTES,
    private val createFile: (name: String, mimeType: String) -> OutputStream?,
    private val deleteFile: (name: String) -> Boolean,
) : ExportDestination {
    override fun createPart(name: String): ExportPart {
        val stream = createFile(name, BackupConstants.ZIP_MIME_TYPE) ?: throw ExportWriteException(name)
        return object : ExportPart {
            override fun write(bytes: ByteArray) {
                stream.write(bytes)
            }

            override fun close() {
                stream.close()
            }

            override fun abort() {
                try {
                    stream.close()
                } catch (_: IOException) {
                    Unit
                }
                deleteFile(name)
            }
        }
    }
}
