package dev.kodakiko.ui.feature.capture

import androidx.compose.ui.unit.dp

object CaptureConstants {
    const val RECORDING_TIMER_INTERVAL_MS = 250L
    const val LOCATION_TIMEOUT_MS = 5_000L
    const val MS_PER_SECOND = 1_000L
    const val SECONDS_PER_MINUTE = 60L
    const val TIMER_DIGITS = 2
    const val TIMER_PAD = '0'
    const val TIMER_SEPARATOR = ":"
    const val REFUSAL_SEPARATOR = "\n"
    const val PACKAGE_SCHEME = "package"
    const val CONTROLS_SCRIM_ALPHA = 0.45f
    const val DISABLED_CONTAINER_ALPHA = 0.12f
    const val DISABLED_CONTENT_ALPHA = 0.38f
    const val TILE_ALPHA = 0.16f
    val VIDEO_FILE_EXTENSIONS: List<String> = listOf(".mp4", ".mov", ".webm")
}

internal object CaptureDimens {
    val SCREEN_PADDING = 16.dp
    val SMALL_SPACING = 8.dp
    val TINY_SPACING = 4.dp
    val TRAY_TILE = 64.dp
    val TRAY_CORNER = 8.dp
    val TRAY_SPINNER = 24.dp
    val REMOVE_BUTTON = 24.dp
    val REMOVE_ICON = 16.dp
    val SHUTTER = 72.dp
    val SHUTTER_RING = 4.dp
    val SHUTTER_INNER = 56.dp
    val SHUTTER_INNER_RECORDING = 28.dp
    val SHUTTER_RECORDING_CORNER = 6.dp
    val SIDE_BUTTON = 48.dp
    val TIMER_PADDING_HORIZONTAL = 12.dp
    val TIMER_PADDING_VERTICAL = 4.dp
    val BUTTON_PROGRESS = 18.dp
    val BUTTON_PROGRESS_STROKE = 2.dp
    val PANEL_MAX_WIDTH = 480.dp
}
