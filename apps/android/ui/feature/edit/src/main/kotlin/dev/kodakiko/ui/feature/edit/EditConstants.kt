package dev.kodakiko.ui.feature.edit

import androidx.compose.ui.unit.dp

object EditConstants {
    const val AUTOSAVE_DEBOUNCE_MS = 600L
    const val VOICE_TICK_MS = 250L
    const val VOICE_MAX_MS = 600_000L
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val MIN_PAGES_FOR_DOTS = 2
    const val PAGE_ASPECT = 0.75f
    const val COORDINATE_DECIMALS = 5
    const val COORDINATE_SEPARATOR = ", "
    const val GEO_SCHEME = "geo:"
    const val GEO_QUERY = "?q="
    const val GEO_SEPARATOR = ","
    const val CLOCK_PAD = 2
    const val CLOCK_PAD_CHAR = '0'
    const val CLOCK_SEPARATOR = ":"
    const val MINUTES_PER_HOUR = 60L
    const val HOURS_PER_DAY = 24L
    const val MS_PER_MINUTE = 60_000L
    const val SINGLE_ITEM = 1
    const val CAPTION_SCRIM_ALPHA = 0.5f
    const val SHADE_ALPHA = 0.55f
    const val HALF = 2f
    const val TAG_OPTION_SEPARATOR = "."
    const val NOTE_MODES = 2
}

internal object EditDimens {
    val SCREEN_PADDING = 16.dp
    val SECTION_SPACING = 16.dp
    val SMALL_SPACING = 8.dp
    val TINY_SPACING = 4.dp
    val TILE_CORNER = 12.dp
    val CHIP_CORNER = 8.dp
    val ROW_PADDING = 8.dp
    val CHIP_PADDING = 12.dp
    val CHIP_ICON = 16.dp
    val CHIP_REMOVE_TARGET = 32.dp
    val SHEET_PADDING = 16.dp
    val DOT = 6.dp
    val ACTIVE_DOT = 16.dp
    val DOT_GAP = 6.dp
    val DOT_TARGET = 24.dp
    val CAPTION_PADDING_HORIZONTAL = 12.dp
    val CAPTION_PADDING_VERTICAL = 4.dp
    val ADD_NOTE_PADDING = 16.dp
    val HAIRLINE = 1.dp
    val PLAY_ICON = 48.dp
    val HANDLE = 28.dp
    val HANDLE_TARGET = 48.dp
    val CROP_LINE = 2.dp
    val CROP_MARGIN = 24.dp
    val SPINNER = 18.dp
    val SPINNER_STROKE = 2.dp
}
