package dev.kodakiko.app.log

import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.log.typeOf
import dev.kodakiko.service.backup.ExportDestination
import dev.kodakiko.service.backup.ExportPhase
import dev.kodakiko.service.backup.ExportProgress
import dev.kodakiko.service.backup.ExportService
import dev.kodakiko.service.backup.ExportSummary
import kotlinx.coroutines.CancellationException

class LoggingExportService(private val inner: ExportService, private val log: DebugLog) : ExportService by inner {
    override suspend fun export(destination: ExportDestination, onProgress: (ExportProgress) -> Unit): ExportSummary {
        val started = System.nanoTime()
        var phase: ExportPhase? = null
        log.record(LogLevel.INFO, LogServices.EXPORT, LogEvents.EXPORT_START)
        try {
            val summary = inner.export(destination) { progress ->
                if (progress.phase != phase) {
                    phase = progress.phase
                    log.record(LogLevel.INFO, LogServices.EXPORT, LogEvents.EXPORT_PHASE, mapOf(LogFields.PHASE to progress.phase))
                }
                onProgress(progress)
            }
            log.record(
                LogLevel.INFO,
                LogServices.EXPORT,
                LogEvents.EXPORT_END,
                mapOf(
                    LogFields.PARTS to summary.parts,
                    LogFields.ENTRIES to summary.entries,
                    LogFields.MEDIA to summary.mediaFiles,
                    LogFields.BYTES to summary.bytes,
                    LogFields.SKIPPED to summary.skippedUnreadable + summary.skippedMissing + summary.skippedTooLarge,
                    LogFields.DURATION_MS to elapsedMs(started),
                ),
            )
            return summary
        } catch (cancelled: CancellationException) {
            log.record(LogLevel.INFO, LogServices.EXPORT, LogEvents.EXPORT_CANCELLED, mapOf(LogFields.DURATION_MS to elapsedMs(started)))
            throw cancelled
        } catch (failure: Throwable) {
            log.record(LogLevel.ERROR, LogServices.EXPORT, LogEvents.EXPORT_ERROR, mapOf(LogFields.TYPE to typeOf(failure), LogFields.DURATION_MS to elapsedMs(started)))
            throw failure
        }
    }
}
