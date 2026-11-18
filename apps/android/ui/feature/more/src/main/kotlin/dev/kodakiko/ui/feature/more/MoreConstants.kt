package dev.kodakiko.ui.feature.more

import androidx.compose.ui.unit.dp

internal object MoreConstants {
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val MS_PER_SECOND = 1_000.0
    const val MS_PER_MINUTE = 60_000.0
    const val MINUTES_PER_HOUR = 60
    const val SYNC_TIME_START = 5
    const val SYNC_TIME_END = 16
    const val MONTH_LABEL_START = 5
    const val MONTH_LABEL_END = 7
    const val BAR_LABEL_SEPARATOR = ": "
    const val TAG_PREFIX = "#"
    const val DAY_RANGE_SEPARATOR = " \u2013 "
    const val FULL_RATIO = 1f
    const val BAR_MIN_MAX = 1
    const val NO_COUNT = ""
}

internal object MoreDimens {
    val LOGO_SIZE = 96.dp
    val LOGO_PADDING = 16.dp
    val SCREEN_PADDING = 16.dp
    val CARD_SPACING = 12.dp
    val CARD_PADDING = 16.dp
    val METRIC_SPACING = 4.dp
    val QUOTA_BAR_HEIGHT = 6.dp
    val BAR_HEIGHT = 120.dp
    val BAR_SPACING = 4.dp
    val SECTION_TOP = 8.dp
}
