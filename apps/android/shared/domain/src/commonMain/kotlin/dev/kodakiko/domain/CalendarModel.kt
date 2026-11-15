package dev.kodakiko.domain

import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsParseInt

fun canShowMonth(yearMonth: String, currentYearMonth: String, monthsWithEntries: Set<String>): Boolean =
    yearMonth <= currentYearMonth || yearMonth in monthsWithEntries

fun monthTarget(from: String, delta: Long, currentYearMonth: String, monthsWithEntries: Set<String>): String? {
    val candidate = addMonths(from, delta)
    if (canShowMonth(candidate, currentYearMonth, monthsWithEntries)) return candidate
    if (delta < 0) return candidate
    return monthsWithEntries.filter { it > candidate }.sorted().firstOrNull()
}

fun pagerMonths(currentYearMonth: String, monthsWithEntries: Set<String>, shownYearMonth: String): List<String> {
    val earliest = (monthsWithEntries + shownYearMonth + currentYearMonth).sorted().firstOrNull() ?: currentYearMonth
    val months = mutableListOf<String>()
    var month = addMonths(earliest, -PAGER_LEAD_MONTHS)
    while (month <= currentYearMonth) {
        months.add(month)
        month = addMonths(month, 1)
    }
    return months + monthsWithEntries.filter { it > currentYearMonth }.sorted()
}

fun isValidLocalDate(value: String): Boolean {
    val match = LOCAL_DATE_PATTERN.matchEntire(value) ?: return false
    val year = jsParseInt(match.groupValues[LOCAL_DATE_YEAR_GROUP])
    val month = jsParseInt(match.groupValues[LOCAL_DATE_MONTH_GROUP])
    val day = jsParseInt(match.groupValues[LOCAL_DATE_DAY_GROUP])
    val fields = JsDate.fields(JsDate.fromUtcParts(year, month - 1, day, 0.0, 0.0, 0.0, 0.0)) ?: return false
    return fields.year == year && fields.monthIndex == month - 1 && fields.day == day
}
