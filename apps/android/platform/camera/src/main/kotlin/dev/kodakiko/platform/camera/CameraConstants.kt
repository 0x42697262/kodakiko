package dev.kodakiko.platform.camera

object CameraConstants {
    const val HOLD_TO_RECORD_MS = 300L
    const val PHOTO_JPEG_QUALITY = 92
    const val NO_FILE_SIZE_LIMIT = 0L
    const val MUXER_RESERVED_BYTES = 400_036L
    const val NOT_BOUND_MESSAGE = "The camera is not bound"
    const val ALREADY_RECORDING_MESSAGE = "A video is already being recorded"
    const val CAP_TOO_SMALL_MESSAGE = "The size cap leaves no room for media after the MP4 container's reserved header"

    fun recordingFailedMessage(error: Int): String = "CameraX finalized the recording with error $error"
}
