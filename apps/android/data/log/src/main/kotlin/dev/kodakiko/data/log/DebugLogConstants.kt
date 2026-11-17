package dev.kodakiko.data.log

object DebugLogConstants {
    const val DIRECTORY = "logs"
    const val FILE_NAME = "debug.log"
    const val FLAG_ON = "debug-log.on"
    const val FLAG_OFF = "debug-log.off"
    const val FLAG_CONTENT = "1"
    const val MAX_BYTES = 1_048_576L
    const val TRIM_TO_BYTES = 524_288L
    const val COPY_MAX_BYTES = 262_144L
    const val MAX_CRASH_FRAMES = 40
    const val MAX_CAUSES = 5
    const val SHARE_DIRECTORY = "share"
    const val SHARE_FILE_NAME = "kodakiko-debug.txt"
    const val STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    const val NEWLINE = "\n"
    const val NEWLINE_BYTE: Byte = 10
    const val READ_MODE = "r"
}
