package dev.kodakiko.ui.core.format

import android.content.res.Resources
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.domain.localTimeOf
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.RangeError
import dev.kodakiko.js.jsSlice
import dev.kodakiko.ui.core.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun englishDate(time: Double, formatter: DateTimeFormatter): String {
    val fields = JsDate.fields(time) ?: throw RangeError(FormatConstants.INVALID_TIME_VALUE)
    return formatter.format(LocalDate.of(fields.year.toInt(), fields.monthIndex.toInt() + 1, fields.day.toInt()))
}

private fun midnight(localDate: String): Double = JsDate.parse(localDate + FormatConstants.MIDNIGHT_SUFFIX)

private fun yearOf(date: String): String = jsSlice(date, 0, FormatConstants.YEAR_LENGTH)

fun formatDayHeader(resources: Resources, localDate: String, today: String): String {
    if (localDate == today) return resources.getString(R.string.gallery_today)
    val yesterday = JsDate.toIsoString(midnight(today) - FormatConstants.MS_PER_DAY).substring(0, FormatConstants.ISO_DATE_LENGTH)
    if (localDate == yesterday) return resources.getString(R.string.gallery_yesterday)
    val formatter = if (yearOf(localDate) == yearOf(today)) FormatConstants.LONG_WEEKDAY_DAY else FormatConstants.FULL_DATE
    return englishDate(midnight(localDate), formatter)
}

fun formatGroupHeader(resources: Resources, key: String, today: String): String {
    FormatConstants.MONTH_KEY.matchEntire(key)?.let { month ->
        return englishDate(midnight(month.groupValues[1] + FormatConstants.FIRST_OF_MONTH), FormatConstants.MONTH_YEAR)
    }
    FormatConstants.YEAR_KEY.matchEntire(key)?.let { year -> return year.groupValues[1] }
    FormatConstants.TAG_KEY.matchEntire(key)?.let { tag -> return FormatConstants.TAG_PREFIX + tag.groupValues[1] }
    if (key == FormatConstants.UNTAGGED_KEY) return resources.getString(R.string.gallery_untagged)
    return formatDayHeader(resources, key, today)
}

fun formatEntryTime(resources: Resources, takenAtUtc: String, utcOffsetMinutes: Long, nowUtc: String, deviceOffsetMinutes: Long): String {
    val entryDate = localDateOf(takenAtUtc, utcOffsetMinutes)
    val time = localTimeOf(takenAtUtc, utcOffsetMinutes)
    val today = localDateOf(nowUtc, deviceOffsetMinutes)
    val yesterday = localDateOf(JsDate.toIsoString(JsDate.parse(nowUtc) - FormatConstants.MS_PER_DAY), deviceOffsetMinutes)
    if (entryDate == today) return resources.getString(R.string.entry_time_today, time)
    if (entryDate == yesterday) return resources.getString(R.string.entry_time_yesterday, time)
    val formatter = if (yearOf(entryDate) == yearOf(today)) FormatConstants.SHORT_WEEKDAY_DAY else FormatConstants.FULL_DATE
    val shifted = JsDate.parse(takenAtUtc) + utcOffsetMinutes * FormatConstants.MS_PER_MINUTE
    return resources.getString(R.string.entry_time_dated, englishDate(shifted, formatter), time)
}
