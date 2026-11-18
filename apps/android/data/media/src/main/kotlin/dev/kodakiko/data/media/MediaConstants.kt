package dev.kodakiko.data.media

import android.media.ExifInterface

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
    const val IMAGE_READ_BYTES = 65_536
    const val THUMBNAIL_MAX_EDGE = 512
    const val THUMBNAIL_QUALITY = 80
    const val POSTER_MAX_TIME_MS = 1_000L
    const val POSTER_DURATION_DIVISOR = 2L
    const val PHOTO_POSTER_TIME_MS = 0L
    const val MICROSECONDS_PER_MILLISECOND = 1_000L
    const val SAMPLE_SIZE_STEP = 2
    const val MIRROR_SCALE = -1f
    private const val QUARTER_TURN = 90
    private const val HALF_TURN = 180
    private const val THREE_QUARTER_TURN = 270
    val AXIS_SWAPPING_DEGREES: Set<Int> = setOf(QUARTER_TURN, THREE_QUARTER_TURN)
    val UPRIGHT = PhotoOrientation(0, false)
    val EXIF_ORIENTATIONS: Map<Int, PhotoOrientation> = mapOf(
        ExifInterface.ORIENTATION_NORMAL to UPRIGHT,
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL to PhotoOrientation(0, true),
        ExifInterface.ORIENTATION_ROTATE_180 to PhotoOrientation(HALF_TURN, false),
        ExifInterface.ORIENTATION_FLIP_VERTICAL to PhotoOrientation(HALF_TURN, true),
        ExifInterface.ORIENTATION_TRANSPOSE to PhotoOrientation(QUARTER_TURN, true),
        ExifInterface.ORIENTATION_ROTATE_90 to PhotoOrientation(QUARTER_TURN, false),
        ExifInterface.ORIENTATION_TRANSVERSE to PhotoOrientation(THREE_QUARTER_TURN, true),
        ExifInterface.ORIENTATION_ROTATE_270 to PhotoOrientation(THREE_QUARTER_TURN, false),
    )
}

internal object MediaKeys {
    const val DOWNLOAD_THREAD_NAME = "kodakiko-download"
    const val THUMBNAIL_PREFETCH_THREAD_NAME = "kodakiko-thumbnail-prefetch"
    const val THUMBNAIL_IMAGE_KEY_PREFIX = "kodakiko-thumbnail:"
    const val ORIGINAL_IMAGE_KEY_PREFIX = "kodakiko-original:"
    const val RETRIEVER_YES = "yes"
    const val READ_ONLY_MODE = "r"
}

internal object MediaText {
    const val READER_CLOSED = "The stream reader is closed."
    const val SOURCE_CLOSED = "The ciphertext source is closed."
    const val DOWNLOAD_CANCELLED = "Download cancelled."
    const val OBJECT_UNAVAILABLE = "Object is no longer available."
    const val MEDIA_UNAVAILABLE = "Media is not available on this device."
    const val PHOTO_UNDECODABLE = "The photo cannot be decoded."
    const val VIDEO_FRAME_UNAVAILABLE = "No frame could be read from the video."
    const val THUMBNAIL_ENCODE_FAILED = "The thumbnail could not be encoded."

    fun sizeMismatch(expected: Long, actual: Long): String = "Downloaded $actual bytes, expected $expected."

    fun downloadHttpStatus(status: Int): String = "HTTP $status"

    fun negativeByteCount(byteCount: Long): String = "Negative byte count: $byteCount"

    fun imageWriteRefused(path: String): String = "The image pipeline never writes files: $path"

    fun unsupportedImageData(type: String?): String = "Unsupported image data: $type"
}
