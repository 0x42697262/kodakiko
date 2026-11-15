package dev.kodakiko.platform.audio

import java.io.File

data class RecordedVoice(val file: File, val durationMs: Long, val mimeType: String)

class RecordAudioPermissionMissingException : SecurityException(AudioConstants.PERMISSION_MISSING_MESSAGE)
