package dev.kodakiko.platform.camera

import java.io.File

sealed interface PhotoResult {
    data class Saved(val file: File) : PhotoResult

    data class Failed(val error: Throwable) : PhotoResult
}

sealed interface VideoResult {
    data class Saved(val file: File, val reachedSizeLimit: Boolean) : VideoResult

    data class Failed(val error: Throwable) : VideoResult
}

class RecordingFailedException(val error: Int, cause: Throwable?) :
    Exception(CameraConstants.recordingFailedMessage(error), cause)
