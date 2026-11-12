package dev.kodakiko.nativeupload

object UploadConstants {
  const val MODULE_NAME = "KodakikoNativeUpload"
  const val EVENT_NAME = "onUploadEvent"
  const val CHANNEL_ID = "uploads"
  const val NOTIFICATION_ID = 4101
  const val PART_CONCURRENCY = 3
  const val TIMEOUT_SECONDS = 300L
  const val CONTENT_TYPE = "application/octet-stream"
  const val COPY_BUFFER_BYTES = 65_536
  const val PERCENT_MAX = 100
  const val PROGRESS_NOTIFY_INTERVAL_MS = 500L
  const val HTTP_FORBIDDEN = 403
  const val FILE_URI_PREFIX = "file://"
  val RETRY_DELAYS_MS: LongArray = longArrayOf(2_000L, 8_000L, 30_000L)
  const val NOTIFICATION_PERMISSION_FIRST_SDK = 33
}
