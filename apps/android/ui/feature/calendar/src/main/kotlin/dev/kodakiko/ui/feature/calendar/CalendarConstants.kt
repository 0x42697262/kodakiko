package dev.kodakiko.ui.feature.calendar

import androidx.compose.ui.unit.dp
import dev.kodakiko.domain.WeekStart
import java.util.Locale

internal object CalendarConstants {
    val WEEK_START: WeekStart = WeekStart.MONDAY
    val LABEL_LOCALE: Locale = Locale.US
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val NEXT_MONTH = 1L
    const val PREVIOUS_MONTH = -1L
    const val SINGLE_ENTRY = 1
    const val DAYS_PER_WEEK = 7
    const val WEEKS_PER_PAGE = 6
    const val YEAR_END = 4
    const val MONTH_START = 5
    const val MONTH_END = 7
    const val DAY_START = 8
    const val DAY_END = 10
    const val TITLE_SEPARATOR = " "
    const val DATE_DAY_SEPARATOR = " "
    const val DATE_YEAR_SEPARATOR = ", "
    const val PAGER_FIRST_MONTH = "1900-01"
    const val JUMP_MONTHS_PER_ROW = 4
}

internal object CalendarDimens {
    val CELL_GAP = 4.dp
    val CELL_CORNER = 6.dp
    val TODAY_RING = 2.dp
    val DAY_NUMBER_PADDING = 4.dp
    val CONTENT_PADDING = 8.dp
    val BUTTON_SPACING = 8.dp
    val SHEET_PADDING = 16.dp
    val SHEET_SPACING = 12.dp
    const val DAY_NUMBER_SHADOW_BLUR = 4f
}
