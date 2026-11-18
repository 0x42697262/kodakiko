package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.exporting.ZipTimeZone
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntriesClock
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.lock.AutoLock
import kotlinx.coroutines.CoroutineDispatcher
import java.io.IOException

enum class ExportPhase {
    PLANNING,
    DOWNLOADING,
    WRITING,
    DONE,
}

data class ExportProgress(
    val phase: ExportPhase,
    val entriesDone: Int,
    val entriesTotal: Int,
    val bytesWritten: Long,
    val partNumber: Int,
    val partsTotal: Int,
)

data class ExportSummary(
    val parts: Int,
    val entries: Int,
    val mediaFiles: Int,
    val bytes: Long,
    val skippedUnreadable: Int,
    val skippedMissing: Int,
    val skippedTooLarge: Int,
)

interface ExportPart {
    fun write(bytes: ByteArray)

    fun close()

    fun abort()
}

interface ExportDestination {
    val partMaxBytes: Long

    fun createPart(name: String): ExportPart
}

class ExportCancelledException : Exception(BackupText.EXPORT_CANCELLED)

class ExportWriteException(val fileName: String) : IOException(BackupText.PART_NOT_CREATED + fileName)

interface BackupFiles {
    fun readRange(objectId: String, offset: Long, length: Int): ByteArray
}

class FileStoreBackupFiles(private val store: () -> FileStore) : BackupFiles {
    override fun readRange(objectId: String, offset: Long, length: Int): ByteArray = store().readRange(objectId, offset, length)
}

interface ExportService {
    suspend fun export(destination: ExportDestination, onProgress: (ExportProgress) -> Unit): ExportSummary

    fun cancel()
}

class ExportServiceDeps(
    val entryIds: suspend () -> List<String>,
    val readMetadata: suspend (String) -> EntryMetadataV1,
    val entryKeys: EntryKeys,
    val rowStore: RowStorePort,
    val files: BackupFiles,
    val fetchObject: suspend (String) -> Unit,
    val enforceCap: suspend () -> Unit,
    val sodium: Sodium,
    val autoLock: AutoLock,
    val clock: EntriesClock,
    val zipTimeZone: ZipTimeZone,
    val ioDispatcher: CoroutineDispatcher,
)
