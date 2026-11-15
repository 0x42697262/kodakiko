package dev.kodakiko.media

import dev.kodakiko.contract.UTC_OFFSET_MINUTES_MAX
import dev.kodakiko.contract.UTC_OFFSET_MINUTES_MIN
import dev.kodakiko.contract.WireEnum
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.RangeError

enum class TimeSource(override val wire: String) : WireEnum {
    EXIF("exif"),
    CONTAINER("container"),
    FILE("file"),
    NOW("now"),
}

data class ResolvedTime(val capturedAtUtc: String, val utcOffsetMinutes: Long, val source: TimeSource)

data class CaptureTimeInput(
    val exifDateTimeOriginal: String?,
    val exifOffset: String?,
    val containerCreationUtc: String?,
    val fileModifiedUtc: String?,
    val nowUtc: String,
    val deviceOffsetMinutes: Long,
)

fun interface TimezoneOffsetSource {
    fun timezoneOffsetMinutes(): Long
}

private fun parseOffsetMinutes(text: String?): Long? {
    if (text == null) return null
    val match = EXIF_OFFSET_PATTERN.matchEntire(text) ?: return null
    val (sign, hoursText, minutesText) = match.destructured
    val hours = hoursText.toLong()
    val minutes = minutesText.toLong()
    if (minutes >= MEDIA_MINUTES_PER_HOUR) return null
    val total = hours * MEDIA_MINUTES_PER_HOUR + minutes
    val signedTotal = if (sign == NEGATIVE_SIGN) 0 - total else total
    return if (signedTotal < UTC_OFFSET_MINUTES_MIN || signedTotal > UTC_OFFSET_MINUTES_MAX) null else signedTotal
}

private fun localMilliseconds(text: String): Double? {
    val match = EXIF_DATE_PARTS_PATTERN.matchEntire(text) ?: return null
    val (yearText, monthText, dayText, hourText, minuteText, secondText) = match.destructured
    val year = yearText.toDouble()
    val month = monthText.toDouble()
    val day = dayText.toDouble()
    val hour = hourText.toDouble()
    val minute = minuteText.toDouble()
    val second = secondText.toDouble()
    val milliseconds = JsDate.utc(year, month - 1, day, hour, minute, second)
    val check = JsDate.fields(milliseconds) ?: return null
    val matches = check.year == year &&
        check.monthIndex == month - 1 &&
        check.day == day &&
        check.hours == hour &&
        check.minutes == minute &&
        check.seconds == second
    return if (matches) milliseconds else null
}

private fun normalizeIso(text: String?): String? {
    if (text == null) return null
    val milliseconds = JsDate.parse(text)
    return if (milliseconds.isNaN()) null else JsDate.toIsoString(milliseconds)
}

fun resolveCaptureTime(input: CaptureTimeInput): ResolvedTime {
    val local = input.exifDateTimeOriginal?.let { localMilliseconds(it) }
    if (local != null) {
        val offset = parseOffsetMinutes(input.exifOffset) ?: input.deviceOffsetMinutes
        return ResolvedTime(JsDate.toIsoString(local - offset * MEDIA_MS_PER_MINUTE.toDouble()), offset, TimeSource.EXIF)
    }
    val container = normalizeIso(input.containerCreationUtc)
    if (container != null) return ResolvedTime(container, input.deviceOffsetMinutes, TimeSource.CONTAINER)
    val file = normalizeIso(input.fileModifiedUtc)
    if (file != null) return ResolvedTime(file, input.deviceOffsetMinutes, TimeSource.FILE)
    return ResolvedTime(input.nowUtc, input.deviceOffsetMinutes, TimeSource.NOW)
}

fun earliestTime(times: List<ResolvedTime>): ResolvedTime {
    val first = times.firstOrNull() ?: throw RangeError(MediaText.NO_TIMES)
    return times.drop(1).fold(first) { earliest, time ->
        if (JsDate.parse(time.capturedAtUtc) < JsDate.parse(earliest.capturedAtUtc)) time else earliest
    }
}

fun deviceOffsetMinutes(date: TimezoneOffsetSource): Long = 0 - date.timezoneOffsetMinutes()
