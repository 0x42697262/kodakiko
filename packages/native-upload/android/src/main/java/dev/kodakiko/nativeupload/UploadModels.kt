package dev.kodakiko.nativeupload

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

sealed class UploadEvent {
  abstract fun toMap(): Map<String, Any?>

  data class Progress(val objectId: String, val sentBytes: Long) : UploadEvent() {
    override fun toMap(): Map<String, Any?> =
      mapOf("type" to "progress", "objectId" to objectId, "sentBytes" to sentBytes.toDouble())
  }

  data class Completed(val objectId: String) : UploadEvent() {
    override fun toMap(): Map<String, Any?> = mapOf("type" to "completed", "objectId" to objectId)
  }

  data class Failed(val objectId: String, val reason: FailureReason) : UploadEvent() {
    override fun toMap(): Map<String, Any?> =
      mapOf("type" to "failed", "objectId" to objectId, "reason" to reason.wire)
  }

  data class Stopped(val reason: String) : UploadEvent() {
    override fun toMap(): Map<String, Any?> = mapOf("type" to "stopped", "reason" to reason)
  }
}
