package dev.kodakiko.platform.audio

object AudioConstants {
    const val VOICE_MAX_MS = 600_000L
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BIT_RATE = 128_000
    const val MIME_TYPE = "audio/mp4"
    const val FILE_EXTENSION = "m4a"
    const val NO_DURATION_MS = 0L
    const val PERMISSION_MISSING_MESSAGE = "RECORD_AUDIO is not granted"
    const val ALREADY_RECORDING_MESSAGE = "A voice note is already being recorded"
}
