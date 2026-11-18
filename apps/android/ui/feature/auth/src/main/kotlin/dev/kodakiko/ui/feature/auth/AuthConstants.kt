package dev.kodakiko.ui.feature.auth

import dev.kodakiko.contract.INVITE_TOKEN_PATTERN
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object AuthConstants {
    val INVITE_TOKEN = Regex(INVITE_TOKEN_PATTERN)
    const val RANDOM_INDEX_LIMIT = 256
    const val RANDOM_INDEX_BYTES = 1
    const val BYTE_MASK = 0xFF
    const val RECOVERY_CONFIRM_COUNT = 3
    const val RECOVERY_GRID_COLUMNS = 2
    const val DELETION_NOTICE_DATE_CHARS = 10
    val DELETION_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US)
}
