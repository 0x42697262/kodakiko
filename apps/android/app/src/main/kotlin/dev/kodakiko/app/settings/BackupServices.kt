package dev.kodakiko.app.settings

import dev.kodakiko.app.log.elapsedMs
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.TempPurpose
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.typeOf
import dev.kodakiko.service.backup.DefaultRestoreService
import dev.kodakiko.service.backup.ExistingEntry
import dev.kodakiko.service.backup.RestoreFile
import dev.kodakiko.service.backup.RestoreMediaFile
import dev.kodakiko.service.backup.RestoreProgress
import dev.kodakiko.service.backup.RestoreService
import dev.kodakiko.service.backup.RestoreServiceDeps
import dev.kodakiko.service.backup.RestoreSummary
import dev.kodakiko.service.entries.EntriesConstants
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.index.IndexService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val WHOLE_JOURNAL = GalleryFilter(GalleryScope.Home, GalleryView.JOURNAL, emptyList(), null, null, "")

private fun ownEntries(index: GalleryIndex?): List<IndexedEntry> {
    if (index == null) return emptyList()
    return index.query(WHOLE_JOURNAL).flatMap { it.entryIds }.mapNotNull(index::get).filter { it.isOwn }
}

suspend fun exportEntryIds(indexService: IndexService): List<String> = withContext(Dispatchers.Main.immediate) {
    ownEntries(indexService.index()).filterNot { it.unreadable }.map { it.id }.reversed()
}

class SnapshotRestoreService(
    private val sodium: Sodium,
    private val index: IndexService,
    private val imports: ImportPipeline,
    private val entries: EntriesService,
    private val tempFiles: TempFiles,
    private val config: () -> PublicConfigDto?,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: DebugLog,
) : RestoreService {
    override suspend fun restore(files: List<RestoreFile>, passphrase: String?, onProgress: (RestoreProgress) -> Unit): RestoreSummary {
        val started = System.nanoTime()
        log.record(
            LogLevel.INFO,
            LogServices.RESTORE,
            LogEvents.RESTORE_START,
            mapOf(LogFields.COUNT to files.size, LogFields.ENCRYPTED to (passphrase != null)),
        )
        try {
            val summary = restoreFiles(files, passphrase, onProgress)
            log.record(
                LogLevel.INFO,
                LogServices.RESTORE,
                LogEvents.RESTORE_END,
                mapOf(
                    LogFields.ENTRIES to summary.entries,
                    LogFields.MEDIA to summary.media,
                    LogFields.DUPLICATES to summary.duplicates,
                    LogFields.FAILED to summary.failed.size,
                    LogFields.DURATION_MS to elapsedMs(started),
                ),
            )
            summary.failed.groupingBy { it.reason }.eachCount().forEach { (reason, count) ->
                log.record(LogLevel.WARN, LogServices.RESTORE, LogEvents.RESTORE_FAILURE, mapOf(LogFields.REASON to reason, LogFields.COUNT to count))
            }
            return summary
        } catch (failure: Throwable) {
            log.record(LogLevel.ERROR, LogServices.RESTORE, LogEvents.RESTORE_ERROR, mapOf(LogFields.TYPE to typeOf(failure), LogFields.DURATION_MS to elapsedMs(started)))
            throw failure
        }
    }

    private suspend fun restoreFiles(files: List<RestoreFile>, passphrase: String?, onProgress: (RestoreProgress) -> Unit): RestoreSummary {
        val existing = withContext(Dispatchers.Main.immediate) {
            ownEntries(index.index()).map { ExistingEntry(it.takenAtUtc, it.mediaCount) }
        }
        val service = DefaultRestoreService(
            RestoreServiceDeps(
                sodium = sodium,
                importMedia = ::importMedia,
                createEntry = entries::createFromMedia,
                setFavorite = { ids, favorite -> entries.setFavorite(ids, favorite) },
                setCaptions = { entryId, captions -> entries.edit(entryId, EntryEdit(captions = captions)) },
                existingEntries = { existing },
                ioDispatcher = ioDispatcher,
            ),
        )
        return service.restore(files, passphrase, onProgress)
    }

    private suspend fun importMedia(file: RestoreMediaFile, position: Int): ImportOutcome {
        val temp = withContext(ioDispatcher) {
            tempFiles.create(TempPurpose.IMPORT, extensionOf(file.fileName)).also { written ->
                try {
                    written.use { out ->
                        file.copyTo { slice ->
                            out.write(slice)
                            wipe(slice)
                        }
                    }
                } catch (failure: Throwable) {
                    written.delete()
                    throw failure
                }
            }
        }
        val limit = (config()?.maxMediaPerEntry ?: EntriesConstants.FALLBACK_MAX_MEDIA_PER_ENTRY).toInt()
        return imports.importOne(MediaInput(MediaOrigin.PICKER, temp.file, file.fileName, null), ImportSlots(used = position, limit = limit))
    }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast(SettingsAdapterConstants.EXTENSION_SEPARATOR, "").takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
            ?: SettingsAdapterConstants.FALLBACK_EXTENSION
}
