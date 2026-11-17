package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

internal object SettingsConstants {
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val LOCATION_PROBE_MS = 10_000L
    const val MIN_PASSPHRASE_CHARS = 12
    const val GALLERY_COLUMNS_MAX = 10
    const val GALLERY_COLUMNS_AUTO = 0
    const val NO_LIMIT = 0L
    const val SYNCED_AT_CHARS = 16
    const val ISO_TIME_SEPARATOR = 'T'
    const val SYNCED_AT_SEPARATOR = ' '
    const val SUMMARY_DASH = " \u2014 "
    const val LABEL_SEPARATOR = ": "
    const val BADGE_TAG_SEPARATOR = "."
    const val FULL_PROGRESS = 1f
    const val DEFAULT_QUOTA_BYTES = 107_374_182_400L
    const val DEFAULT_DELETION_GRACE_DAYS = 7L
    const val GIB = 1_073_741_824.0
    const val DEFAULT_INVITE_DAYS = "7"
    const val DATE_CHARS = 10
    const val HTTP_DEFAULT_PORT = 80
    const val HTTPS_DEFAULT_PORT = 443
    const val HTTPS_SCHEME = "https"
    const val NO_PORT = -1
    const val PORT_SEPARATOR = ":"
    const val NOTE_OPEN = " ("
    const val NOTE_CLOSE = ")"
    const val WORD_NUMBER_SUFFIX = ". "
    const val LINE_BREAK = "\n"
    const val RECOVERY_GRID_COLUMNS = 2
    const val UNKNOWN_VALUE = "\u2014"
    const val LICENSE_SEPARATOR = " \u00b7 "
    const val WORD_SEPARATOR = " "
    const val REFUSAL_REASON = "reason"
    const val SELF_DEMOTION = "selfDemotion"
    const val LAST_ADMIN = "lastAdmin"

    val AUTO_LOCK_MINUTES: List<Int> = listOf(1, 2, 5, 10, 15, 30, 60)
    val HOLD_TO_RECORD_SECONDS: List<Int> = listOf(5, 10, 15, 30, 60, 120)
    val ORIGINAL_CACHE_CAPS: List<Long> = listOf(536_870_912L, 1_073_741_824L, 2_147_483_648L, 5_368_709_120L, 10_737_418_240L, NO_LIMIT)
    val LARGE_UPLOAD_THRESHOLDS: List<Long> = listOf(52_428_800L, 104_857_600L, 262_144_000L, 524_288_000L, 1_073_741_824L)
}

internal object SettingsDimens {
    val SCREEN_PADDING = 16.dp
    val SECTION_SPACING = 12.dp
    val ITEM_SPACING = 8.dp
    val USAGE_BAR_HEIGHT = 8.dp
    val USAGE_TOP_PADDING = 8.dp
    val VALUE_SPACING = 2.dp
    val ROW_SPACING = 4.dp
    val WORD_NUMBER_WIDTH = 32.dp
    val CARD_PADDING = 12.dp
    val BORDER_WIDTH = 1.dp
    val BUTTON_PROGRESS_SIZE = 18.dp
    val BUTTON_PROGRESS_STROKE = 2.dp
    val ITEM_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
}
