package dev.kodakiko.ui.feature.gallery

import androidx.compose.ui.unit.dp

internal object GalleryConstants {
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val SCROLL_HIDE_THRESHOLD = 24f
    const val SAVED_NOTICE_MS = 2_000L
    const val TRASH_DAYS_FALLBACK = 30L
    const val ON_THIS_DAY_TILE_LIMIT = 10
    const val INITIAL_SEED = 1.0
    const val SEED_STEP = 1.0
    const val DATE_MIDNIGHT = "00:00"
    const val UTC_OFFSET_ZERO = 0L
    const val DATE_FIELD_SEPARATOR = " "
    const val TAG_PREFIX = "#"
    const val TAG_SEPARATOR = " "
    const val COMPACT_SEPARATOR = " · "
    val MARKDOWN_LEAD = Regex("^[#>*\\-\\s]+")
    const val SINGLE_MEDIA = 1
    const val COLUMNS_MAX = 10
    const val COLUMNS_STEPS = 9
    const val LIST_COLUMNS = 1
    const val LIST_NOTE_LINES = 2
    const val SENTENCE_SEPARATOR = " "
    const val KEY_ON_THIS_DAY = "onThisDay"
    const val KEY_HEADER = "header:"
    const val KEY_TILE = "tile:"
    const val KEY_FOOTER = "footer"
    const val TAG_PART_SEPARATOR = "."
    const val BADGE_FAVORITE = "favorite"
    const val BADGE_VIDEO = "video"
    const val BADGE_PHOTO_COUNT = "photoCount"
    const val BADGE_NOTE = "note"
    const val BADGE_PENDING = "pending"
}

internal object GalleryDimens {
    val TILE_GAP = 2.dp
    val GRID_PADDING = 8.dp
    val TILE_CORNER = 8.dp
    val LIST_THUMB = 72.dp
    val BADGE_OFFSET = 4.dp
    val BADGE_SPACING = 2.dp
    val CHECKBOX_PADDING = 2.dp
    val SELECTED_BORDER = 3.dp
    val CAPTION_PADDING = 4.dp
    val ON_THIS_DAY_TILE = 72.dp
    val ON_THIS_DAY_SPACING = 4.dp
    val ON_THIS_DAY_GROUP_SPACING = 16.dp
    val CHIP_SPACING = 8.dp
    val CHIP_ICON = 18.dp
    val PLACEHOLDER_ICON = 32.dp
    val LOCK_ICON = 24.dp
    const val COMPACT_SHADE_ALPHA = 0.55f
    const val PLACEHOLDER_ALPHA = 0.38f
}
