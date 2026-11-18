package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.createStreamDecryptor
import dev.kodakiko.crypto.utf8Encode
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.exporting.EXPORT_MISSING_FILE
import dev.kodakiko.exporting.EXPORT_PART_MAX_FILES
import dev.kodakiko.exporting.EXPORT_README_FILE
import dev.kodakiko.exporting.ExportEntryPlan
import dev.kodakiko.exporting.ExportText
import dev.kodakiko.exporting.PlannedFile
import dev.kodakiko.exporting.ZipWriter
import dev.kodakiko.exporting.partFileName
import dev.kodakiko.exporting.planEntryExport
import dev.kodakiko.exporting.planParts
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsStableSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.sign

private enum class JobKind {
    NOTE,
    MEDIA,
}

private class FileJob(val entryId: String, val path: String, val bytes: Long, val kind: JobKind, val plan: ExportEntryPlan, val fileName: String, val mediaId: String?)

private class PlannedEntry(val entryId: String, val metadata: EntryMetadataV1, val plan: ExportEntryPlan)

private class Skipped(val folder: String, val file: String, val reason: String)

private class WrittenMedia(val bytes: Long)

private fun timeOrder(left: PlannedEntry, right: PlannedEntry): Int {
    val difference = JsDate.parse(left.metadata.takenAtUtc) - JsDate.parse(right.metadata.takenAtUtc)
    return if (difference.isNaN()) 0 else difference.sign.toInt()
}

class DefaultExportService(private val deps: ExportServiceDeps) : ExportService {
    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    override suspend fun export(destination: ExportDestination, onProgress: (ExportProgress) -> Unit): ExportSummary {
        cancelled = false
        deps.autoLock.pause()
        try {
            return withContext(deps.ioDispatcher) { Run(destination, onProgress).execute() }
        } finally {
            deps.autoLock.resume()
        }
    }

    private suspend fun check() {
        currentCoroutineContext().ensureActive()
        if (cancelled) throw ExportCancelledException()
    }

    private inner class Run(private val destination: ExportDestination, private val onProgress: (ExportProgress) -> Unit) {
        private var part: ExportPart? = null

        suspend fun execute(): ExportSummary =
            try {
                plannedRun()
            } catch (error: Throwable) {
                part?.let(::abortQuietly)
                when {
                    error is CancellationException -> throw error
                    cancelled -> throw ExportCancelledException()
                    else -> throw error
                }
            }

        private fun abortQuietly(target: ExportPart) {
            try {
                target.abort()
            } catch (_: Exception) {
                Unit
            }
        }

        private suspend fun planEntries(): Pair<List<PlannedEntry>, Int> {
            val entries = mutableListOf<PlannedEntry>()
            var unreadable = 0
            for (entryId in deps.entryIds()) {
                try {
                    val metadata = deps.readMetadata(entryId)
                    entries += PlannedEntry(entryId, metadata, planEntryExport(entryId, metadata))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    unreadable += 1
                }
            }
            return jsStableSort(entries, ::timeOrder) to unreadable
        }

        private suspend fun plannedRun(): ExportSummary {
            onProgress(ExportProgress(ExportPhase.PLANNING, 0, 0, 0, 0, 0))
            val (entries, skippedUnreadable) = planEntries()
            val skipped = mutableListOf<Skipped>()
            var skippedTooLarge = 0
            val jobs = mutableListOf<FileJob>()
            for (entry in entries) {
                val plan = entry.plan
                jobs += FileJob(entry.entryId, plan.noteFile, utf8Encode(plan.noteText).size.toLong(), JobKind.NOTE, plan, "", null)
                for (media in plan.media) {
                    if (media.plaintextBytes > destination.partMaxBytes) {
                        skippedTooLarge += 1
                        skipped += Skipped(plan.folder, media.fileName, BackupConstants.REASON_TOO_LARGE)
                        continue
                    }
                    jobs += FileJob(entry.entryId, plan.folder + media.fileName, media.plaintextBytes, JobKind.MEDIA, plan, media.fileName, media.mediaId)
                }
            }
            val plannedParts = planParts(jobs.map { PlannedFile(it.entryId, it.path, it.bytes) }, destination.partMaxBytes, EXPORT_PART_MAX_FILES)
            var next = 0
            val parts = plannedParts.map { files -> files.map { jobs[next++] } }
            val exportDate = deps.clock.nowIso().take(BackupConstants.DATE_CHARS)
            var bytesWritten = 0L
            var mediaFiles = 0
            val done = HashSet<String>()
            for ((partIndex, files) in parts.withIndex()) {
                val partNumber = partIndex + BackupConstants.FIRST_PART
                check()
                val sink = destination.createPart(partFileName(exportDate, partNumber.toLong()))
                part = sink
                val writer = ZipWriter({ bytes -> sink.write(bytes) }, deps.zipTimeZone)
                if (partIndex == 0) {
                    writer.addFile(EXPORT_README_FILE, compress = false, mtime = deps.clock.now().toDouble()).push(utf8Encode(ExportText.readme(exportDate)), true)
                }
                for (job in files) {
                    check()
                    val progress = ExportProgress(ExportPhase.WRITING, done.size, entries.size, bytesWritten, partNumber, parts.size)
                    onProgress(progress)
                    if (job.kind == JobKind.NOTE) {
                        val bytes = utf8Encode(job.plan.noteText)
                        writer.addFile(job.path, compress = true, mtime = job.plan.mtime).push(bytes, true)
                        bytesWritten += bytes.size
                    } else {
                        onProgress(progress.copy(phase = ExportPhase.DOWNLOADING))
                        val written = writeMedia(job, writer)
                        if (written == null) {
                            skipped += Skipped(job.plan.folder, job.fileName, BackupConstants.REASON_MISSING)
                        } else {
                            bytesWritten += written.bytes
                            mediaFiles += 1
                        }
                    }
                    if (job.kind == JobKind.MEDIA || job.plan.media.isEmpty()) {
                        done += job.entryId
                        deps.enforceCap()
                    }
                }
                if (partIndex == parts.size - 1 && skipped.isNotEmpty()) {
                    val lines = listOf(ExportText.MISSING_HEADER) + skipped.map { ExportText.missingLine(it.folder, it.file, it.reason) }
                    writer.addFile(EXPORT_MISSING_FILE, compress = true, mtime = deps.clock.now().toDouble())
                        .push(utf8Encode(lines.joinToString(BackupConstants.LINE_BREAK) + BackupConstants.LINE_BREAK), true)
                }
                writer.finish()
                check()
                sink.close()
                part = null
            }
            onProgress(ExportProgress(ExportPhase.DONE, entries.size, entries.size, bytesWritten, parts.size, parts.size))
            return ExportSummary(
                parts = parts.size,
                entries = entries.size,
                mediaFiles = mediaFiles,
                bytes = bytesWritten,
                skippedUnreadable = skippedUnreadable,
                skippedMissing = skipped.count { it.reason == BackupConstants.REASON_MISSING },
                skippedTooLarge = skippedTooLarge,
            )
        }

        private fun originalRow(mediaId: String): ObjectRow? =
            deps.rowStore.transaction { tx -> tx.objects.byMedia(mediaId) }.firstOrNull { it.variant == ObjectVariant.ORIGINAL }

        private suspend fun presentOriginal(mediaId: String): ObjectRow? {
            val row = originalRow(mediaId)
            if (row == null || row.localState == ObjectLocalState.UNAVAILABLE) return null
            if (row.localState == ObjectLocalState.PRESENT) return row
            try {
                deps.fetchObject(row.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
            return originalRow(mediaId)?.takeIf { it.localState == ObjectLocalState.PRESENT }
        }

        private suspend fun writeMedia(job: FileJob, writer: ZipWriter): WrittenMedia? {
            val mediaId = job.mediaId.orEmpty()
            val row = presentOriginal(mediaId) ?: return null
            val key = deps.entryKeys.variantKey(job.entryId, mediaId, ObjectVariant.ORIGINAL)
            try {
                val header = deps.files.readRange(row.id, 0, STREAM_HEADER_BYTES)
                val decryptor = createStreamDecryptor(deps.sodium, key, header, row.plaintextSize)
                val file = writer.addFile(job.plan.folder + job.fileName, compress = false, mtime = job.plan.mtime)
                val step = decryptor.layout.chunkCiphertextSize.toLong()
                val chunkCount = decryptor.layout.chunkCount
                var written = 0L
                for (index in 0 until chunkCount) {
                    check()
                    val offset = STREAM_HEADER_BYTES + index * step
                    val chunk = deps.files.readRange(row.id, offset, minOf(step, row.sizeBytes - offset).toInt())
                    val plain = decryptor.decryptNext(chunk)
                    written += plain.size
                    file.push(plain, index == chunkCount - 1)
                }
                decryptor.finish()
                return WrittenMedia(written)
            } finally {
                wipe(key)
            }
        }
    }
}
