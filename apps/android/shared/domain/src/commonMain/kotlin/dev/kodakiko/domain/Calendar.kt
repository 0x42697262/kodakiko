package dev.kodakiko.domain

import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsIntegerString
import dev.kodakiko.js.jsParseInt
import kotlin.math.floor

data class CalendarCell(val localDate: String, val inMonth: Boolean, val isToday: Boolean)

enum class WeekStart(val dayIndex: Int) {
    SUNDAY(0),
    MONDAY(1),
}

private class YearMonth(val year: Double, val monthIndex: Double)

private fun parseYearMonth(yearMonth: String): YearMonth {
    val parts = yearMonth.split(DATE_PART_SEPARATOR)
    return YearMonth(jsParseInt(parts.getOrElse(0) { "" }), jsParseInt(parts.getOrElse(1) { "" }) - 1)
}

private fun formatYearMonth(year: Double, monthIndex: Double): String {
    val yearText = jsIntegerString(year).padStart(YEAR_DIGITS, DATE_PAD_CHAR)
    val monthText = jsIntegerString(monthIndex + 1).padStart(MONTH_DIGITS, DATE_PAD_CHAR)
    return "$yearText$DATE_PART_SEPARATOR$monthText"
}

private fun utcDate(year: Double, monthIndex: Double, day: Double): Double = JsDate.fromUtcParts(year, monthIndex, day, 0.0, 0.0, 0.0, 0.0)

private fun localDateOfUtc(time: Double): String {
    val year = JsDate.fields(time)?.year ?: Double.NaN
    val yearText = jsIntegerString(year).padStart(YEAR_DIGITS, DATE_PAD_CHAR)
    return yearText + JsDate.toIsoString(time).substring(YEAR_DIGITS, ISO_DATE_LENGTH)
}

fun isValidYearMonth(value: String): Boolean = YEAR_MONTH_PATTERN.matches(value)

fun yearMonthOf(localDate: String): String = localDate.take(YEAR_MONTH_LENGTH)

fun addMonths(yearMonth: String, delta: Long): String {
    val parsed = parseYearMonth(yearMonth)
    val total = parsed.year * MONTHS_PER_YEAR + parsed.monthIndex + delta
    val nextYear = floor(total / MONTHS_PER_YEAR)
    return formatYearMonth(nextYear, total - nextYear * MONTHS_PER_YEAR)
}

fun monthGrid(yearMonth: String, todayLocalDate: String, weekStartsOn: WeekStart): List<List<CalendarCell>> {
    val parsed = parseYearMonth(yearMonth)
    val first = utcDate(parsed.year, parsed.monthIndex, FIRST_DAY_OF_MONTH)
    val weekDay = JsDate.fields(first)?.weekDay ?: Double.NaN
    val leading = (weekDay - weekStartsOn.dayIndex + DAYS_PER_WEEK) % DAYS_PER_WEEK
    val startMs = first - leading * MS_PER_DAY
    return (0 until CALENDAR_ROWS).map { row ->
        (0 until DAYS_PER_WEEK).map { column ->
            val date = JsDate.timeClip(startMs + (row * DAYS_PER_WEEK + column) * MS_PER_DAY)
            val localDate = localDateOfUtc(date)
            val fields = JsDate.fields(date)
            CalendarCell(
                localDate = localDate,
                inMonth = fields != null && fields.year == parsed.year && fields.monthIndex == parsed.monthIndex,
                isToday = localDate == todayLocalDate,
            )
        }
    }
}
