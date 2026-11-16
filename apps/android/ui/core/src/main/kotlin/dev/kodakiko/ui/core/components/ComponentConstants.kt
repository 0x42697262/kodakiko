// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object TabbedDialogPaddings {
    val Horizontal = 24.dp
    val Vertical = 8.dp
}

object SettingsItemsPaddings {
    val Horizontal = 24.dp
    val Vertical = 10.dp
}

object TabBadgeStyle {
    const val ALPHA_DARK = 0.12f
    const val ALPHA_LIGHT = 0.08f
    val FONT_SIZE = 10.sp
    const val CENTER_DIVISOR = 2
}

internal object ComponentConstants {
    val ACTION_MODE_ELEVATION = 3.dp
    const val SUBTITLE_MARQUEE_REPEAT_DELAY_MILLIS = 2_000
    val SEARCH_FIELD_FONT_SIZE = 18.sp
    const val SEARCH_ACTION_KEY = "search"
    const val TOOLBAR_ACTIONS_KEY = "actions"

    val MENU_OFFSET_X = 8.dp
    val MENU_OFFSET_Y = (-56).dp
    val MENU_WIDTH = 196.dp

    const val TABLET_UI_MIN_SCREEN_WIDTH_DP = 720
    val SHEET_MAX_WIDTH = 460.dp
    val SHEET_TABLET_VERTICAL_PADDING = 16.dp
    const val SHEET_ANIMATION_MILLIS = 350
    const val SHEET_DISMISS_FRACTION = 0.3f
    val SHEET_DISMISS_VELOCITY = 1000.dp

    val BADGE_PADDING_HORIZONTAL = 3.dp
    val BADGE_PADDING_VERTICAL = 1.dp
    const val BADGE_ICON_PLACEHOLDER = "[icon]"

    val BANNER_PADDING = 12.dp
    val FIELD_ERROR_SPACING = 4.dp
    val BANNER_ICON_SPACING = 12.dp
    val BUTTON_PROGRESS_SIZE = 18.dp
    val BUTTON_PROGRESS_STROKE = 2.dp
    val BUTTON_PROGRESS_SPACING = 8.dp

    val PILL_PADDING_START = 4.dp
    val PILL_PADDING_HORIZONTAL = 6.dp
    val PILL_PADDING_VERTICAL = 1.dp

    val SETTINGS_WIDGET_SPACING = 24.dp
    val SORT_ICON_PLACEHOLDER_SIZE = 24.dp
    val SLIDER_ITEM_SPACING = 2.dp

    val THUMB_LENGTH = 48.dp
    val THUMB_THICKNESS = 12.dp
    val THUMB_CORNER = 6.dp
    val LIST_THUMB_HORIZONTAL_PADDING = 8.dp
    val SCROLL_BAR_VISIBILITY_DURATION = 2.seconds
    val SCROLL_REVEAL_SAMPLE_PERIOD = 100.milliseconds
    const val STICKY_HEADER_KEY_PREFIX = "sticky:"
    const val TYPE_SIZE_SLOTS = 2
}
