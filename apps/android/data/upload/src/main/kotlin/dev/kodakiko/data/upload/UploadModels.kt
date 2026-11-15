package dev.kodakiko.data.upload

data class UploadPart(
    val partNumber: Int,
    val url: String,
    val offset: Long,
    val length: Long,
)

data class UploadJob(
    val objectId: String,
    val filePath: String,
    val parts: List<UploadPart>,
) {
    val totalBytes: Long get() = parts.sumOf { it.length }
}

enum class UploadStatus(val wire: String) {
    QUEUED("queued"),
    UPLOADING("uploading"),
    COMPLETED("completed"),
    FAILED("failed"),
}

enum class FailureReason(val wire: String) {
    EXPIRED("expired"),
    NETWORK("network"),
    HTTP("http"),
    MISSING_FILE("missingFile"),
}

enum class StopReason(val wire: String) {
    TIMEOUT("timeout"),
}

sealed class UploadEvent {
    data class Progress(val objectId: String, val sentBytes: Long) : UploadEvent()

    data class Completed(val objectId: String) : UploadEvent()

    data class Failed(val objectId: String, val reason: FailureReason) : UploadEvent()

    data class Stopped(val reason: StopReason) : UploadEvent()
}
