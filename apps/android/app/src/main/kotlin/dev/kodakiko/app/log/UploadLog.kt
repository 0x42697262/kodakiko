package dev.kodakiko.app.log

import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices

fun DebugLog.uploadEvent(kind: String, event: UploadEvent) {
    when (event) {
        is UploadEvent.Progress -> Unit
        is UploadEvent.Completed ->
            record(LogLevel.INFO, LogServices.UPLOAD, LogEvents.COMPLETED, mapOf(LogFields.KIND to kind, LogFields.OBJECT_ID to event.objectId))
        is UploadEvent.Failed ->
            record(LogLevel.WARN, LogServices.UPLOAD, LogEvents.FAILED, mapOf(LogFields.KIND to kind, LogFields.OBJECT_ID to event.objectId, LogFields.REASON to event.reason))
        is UploadEvent.Stopped ->
            record(LogLevel.INFO, LogServices.UPLOAD, LogEvents.STOPPED, mapOf(LogFields.KIND to kind, LogFields.REASON to event.reason))
    }
}
