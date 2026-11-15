package dev.kodakiko.data.media

internal object MediaConstants {
    const val STREAM_CHUNK_CACHE_ITEMS = 4
    const val ORIGINAL_CACHE_NO_LIMIT = 0L
    const val THUMBNAIL_FETCH_CONCURRENCY = 6
    const val ORIGINAL_FETCH_CONCURRENCY = 2
    const val DOWNLOAD_TIMEOUT_MS = 600_000L
    val DOWNLOAD_RETRY_DELAYS_MS: List<Long> = listOf(2_000L, 8_000L, 30_000L)
    const val DOWNLOAD_BUFFER_BYTES = 65_536
    const val DOWNLOAD_THREAD_KEEP_ALIVE_MS = 60_000L
    const val NO_TIMEOUT = 0L
    const val HTTP_NOT_FOUND = 404
    const val HTTP_SERVER_ERROR_MIN = 500
    const val END_OF_STREAM = -1
    const val THUMBNAIL_MEMORY_CACHE_ITEMS = 300
}

internal object MediaKeys {
    const val DOWNLOAD_THREAD_NAME = "kodakiko-download"
    const val THUMBNAIL_PREFETCH_THREAD_NAME = "kodakiko-thumbnail-prefetch"
}

internal object MediaText {
    const val READER_CLOSED = "The stream reader is closed."
    const val SOURCE_CLOSED = "The ciphertext source is closed."
    const val DOWNLOAD_CANCELLED = "Download cancelled."
    const val OBJECT_UNAVAILABLE = "Object is no longer available."

    fun sizeMismatch(expected: Long, actual: Long): String = "Downloaded $actual bytes, expected $expected."

    fun downloadHttpStatus(status: Int): String = "HTTP $status"
}
