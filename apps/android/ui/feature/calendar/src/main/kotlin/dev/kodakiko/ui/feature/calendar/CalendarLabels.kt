package dev.kodakiko.ui.feature.calendar

import android.content.res.Resources
import dev.kodakiko.domain.WeekStart
import dev.kodakiko.ui.core.R
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle

object CalendarLabels {
    private fun part(value: String, start: Int, end: Int): Int = value.substring(start, end).toInt()

    private fun monthName(yearMonthOrDate: String): String =
        Month.of(part(yearMonthOrDate, CalendarConstants.MONTH_START, CalendarConstants.MONTH_END))
            .getDisplayName(TextStyle.FULL, CalendarConstants.LABEL_LOCALE)

    private fun year(yearMonthOrDate: String): Int = part(yearMonthOrDate, 0, CalendarConstants.YEAR_END)

    fun monthTitle(yearMonth: String): String = monthName(yearMonth) + CalendarConstants.TITLE_SEPARATOR + year(yearMonth)

    fun weekdayNames(weekStart: WeekStart): List<String> = (0 until CalendarConstants.DAYS_PER_WEEK).map { position ->
        val sundayBased = (position + weekStart.dayIndex) % CalendarConstants.DAYS_PER_WEEK
        val isoDay = if (sundayBased == 0) DayOfWeek.SUNDAY else DayOfWeek.of(sundayBased)
        isoDay.getDisplayName(TextStyle.SHORT, CalendarConstants.LABEL_LOCALE)
    }

    fun dayNumber(localDate: String): String = part(localDate, CalendarConstants.DAY_START, CalendarConstants.DAY_END).toString()

    fun longDate(localDate: String): String =
        monthName(localDate) + CalendarConstants.DATE_DAY_SEPARATOR + dayNumber(localDate) + CalendarConstants.DATE_YEAR_SEPARATOR + year(localDate)

    fun cellLabel(resources: Resources, localDate: String, count: Int): String {
        val date = longDate(localDate)
        return when (count) {
            0 -> resources.getString(R.string.calendar_cell_no_entries, date)
            CalendarConstants.SINGLE_ENTRY -> resources.getString(R.string.calendar_cell_one_entry, date)
            else -> resources.getString(R.string.calendar_cell_entries, date, count.toString())
        }
    }
}
