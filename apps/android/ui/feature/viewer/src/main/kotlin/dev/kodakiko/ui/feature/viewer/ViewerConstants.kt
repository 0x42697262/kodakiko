package dev.kodakiko.ui.feature.viewer

import androidx.compose.ui.unit.dp

internal object ViewerConstants {
    const val CAPTION_MAX_LINES = 2
    const val CAPTION_SCRIM_ALPHA = 0.55f
    const val ZOOM_MIN = 1.0
    const val ZOOM_MAX = 5.0
    const val ZOOM_DOUBLE_TAP = 2.5
    const val ZOOM_SNAP_BELOW = 1.05
    const val ZOOM_UNZOOMED = 1.0
    const val HALF = 2.0
    const val AUTOPLAY_SETTLE_MS = 800L
    const val ORIGINAL_WINDOW = 1
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val PAGE_ASPECT = 4f / 3f
    const val PAGE_MAX_SCREEN_SHARE = 0.7f
    const val PERCENT = 100
    const val COORDINATE_DECIMALS = 5
    const val COORDINATE_SEPARATOR = ", "
    const val GEO_SCHEME = "geo:"
    const val GEO_QUERY = "?q="
    const val GEO_SEPARATOR = ","
    const val SINGLE_ENTRY = 1
}

internal object ViewerDimens {
    val CAPTION_PADDING_HORIZONTAL = 12.dp
    val CAPTION_PADDING_VERTICAL = 6.dp
    val SCREEN_PADDING = 16.dp
    val SECTION_SPACING = 12.dp
    val SMALL_SPACING = 8.dp
    val TINY_SPACING = 4.dp
    val LABEL_CORNER = 6.dp
    val TILE_CORNER = 12.dp
    val SHEET_PADDING = 16.dp
    val ROW_PADDING = 8.dp
    val CONTROL_PADDING = 16.dp
    const val SCRIM_ALPHA = 0.6f
}
