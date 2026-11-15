package dev.kodakiko.domain

import dev.kodakiko.media.JsDate

private fun shiftedIso(takenAtUtc: String, utcOffsetMinutes: Long): String =
    JsDate.toIsoString(JsDate.parse(takenAtUtc) + utcOffsetMinutes * MS_PER_MINUTE)

fun localDateOf(takenAtUtc: String, utcOffsetMinutes: Long): String = shiftedIso(takenAtUtc, utcOffsetMinutes).substring(0, ISO_DATE_LENGTH)

fun localTimeOf(takenAtUtc: String, utcOffsetMinutes: Long): String =
    shiftedIso(takenAtUtc, utcOffsetMinutes).substring(ISO_TIME_START, ISO_TIME_END)

fun localPartsToUtc(date: String, time: String, utcOffsetMinutes: Long): String? {
    val dateMatch = LOCAL_DATE_PATTERN.matchEntire(date)
    val timeMatch = LOCAL_TIME_PATTERN.matchEntire(time)
    if (dateMatch == null || timeMatch == null) return null
    val (yearText, monthText, dayText) = dateMatch.destructured
    val (hoursText, minutesText) = timeMatch.destructured
    val year = jsParseInt(yearText)
    val month = jsParseInt(monthText)
    val day = jsParseInt(dayText)
    val hours = jsParseInt(hoursText)
    val minutes = jsParseInt(minutesText)
    if (hours >= HOURS_PER_DAY || minutes >= MINUTES_PER_HOUR) return null
    val local = JsDate.fromUtcParts(year, month - 1, day, hours, minutes, 0.0, 0.0)
    val parts = JsDate.fields(local)
    val isRealDate = parts != null && parts.year == year && parts.monthIndex == month - 1 && parts.day == day
    if (!isRealDate) return null
    val utc = JsDate.timeClip(local - utcOffsetMinutes * MS_PER_MINUTE)
    val utcYear = JsDate.fields(utc)?.year ?: Double.NaN
    return if (utcYear < YEAR_MIN || utcYear > YEAR_MAX) null else JsDate.toIsoString(utc)
}

fun currentTimeZone(platformZone: String?): String? = platformZone?.takeIf { it.isNotEmpty() }
