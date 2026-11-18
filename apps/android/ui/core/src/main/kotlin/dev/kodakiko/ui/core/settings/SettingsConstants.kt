// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.seconds

internal object SettingsConstants {
    val PREFERENCE_MIN_HEIGHT = 56.dp
    val TRAILING_WIDGET_BUFFER = 16.dp
    val PREFS_HORIZONTAL_PADDING = 16.dp
    val PREFS_VERTICAL_PADDING = 16.dp
    val ICON_END_PADDING = 8.dp
    val TITLE_FONT_SIZE = 16.sp
    const val TITLE_MAX_LINES = 2
    const val SUBTITLE_MAX_LINES = 10

    val GROUP_HEADER_TOP_PADDING = 14.dp
    val GROUP_HEADER_BOTTOM_PADDING = 8.dp
    val GROUP_SPACER_HEIGHT = 12.dp

    val DIALOG_LABEL_START_PADDING = 24.dp
    val DIALOG_MESSAGE_BOTTOM_PADDING = 8.dp

    val HIGHLIGHT_SCROLL_DELAY = 0.5.seconds
    val HIGHLIGHT_DURATION = 3.seconds
    const val HIGHLIGHT_ALPHA = 0.12f
    const val HIGHLIGHT_ITERATIONS = 5
    const val HIGHLIGHT_STEP_MILLIS = 200
    const val HIGHLIGHT_START_OFFSET_MILLIS = 600
    const val HIGHLIGHT_LABEL = "highlight"
    const val HIGHLIGHTED_PROPERTY = "PreferenceHighlighted"

    val SEARCH_RESULT_HORIZONTAL_PADDING = 24.dp
    val SEARCH_RESULT_VERTICAL_PADDING = 14.dp
    val SEARCH_BREADCRUMB_BASELINE = 16.dp
    const val SEARCH_RESULTS_LABEL = "results"

    const val BREADCRUMB_SEPARATOR = " > "
    const val OPTION_TAG_SEPARATOR = "."
    const val DIALOG_TAG_SUFFIX = "dialog"
    const val SWITCH_TAG_SUFFIX = "switch"
    const val VALUES_SEPARATOR = ", "
    const val ELLIPSIS = '\u2026'
}
