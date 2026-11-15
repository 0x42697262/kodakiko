package dev.kodakiko.media

import dev.kodakiko.media.JsDateConstants.CENTURY_YEARS
import dev.kodakiko.media.JsDateConstants.DAYS_PER_ERA
import dev.kodakiko.media.JsDateConstants.DAYS_PER_WEEK
import dev.kodakiko.media.JsDateConstants.DAYS_PER_YEAR
import dev.kodakiko.media.JsDateConstants.ERA_CENTURY_DAYS
import dev.kodakiko.media.JsDateConstants.ERA_DAY_OFFSET
import dev.kodakiko.media.JsDateConstants.ERA_FOUR_YEAR_DAYS
import dev.kodakiko.media.JsDateConstants.ERA_LAST_DAY
import dev.kodakiko.media.JsDateConstants.LEAP_CYCLE_YEARS
import dev.kodakiko.media.JsDateConstants.MARCH_BASED_DAY_ROUNDING
import dev.kodakiko.media.JsDateConstants.MARCH_BASED_MONTH_DAYS
import dev.kodakiko.media.JsDateConstants.MARCH_BASED_MONTH_OFFSET
import dev.kodakiko.media.JsDateConstants.MARCH_SHIFT_BACK
import dev.kodakiko.media.JsDateConstants.MARCH_SHIFT_FORWARD
import dev.kodakiko.media.JsDateConstants.MARCH_SHIFT_LIMIT
import dev.kodakiko.media.JsDateConstants.MONTHS_PER_YEAR
import dev.kodakiko.media.JsDateConstants.MS_PER_DAY
import dev.kodakiko.media.JsDateConstants.MS_PER_HOUR
import dev.kodakiko.media.JsDateConstants.MS_PER_MINUTE
import dev.kodakiko.media.JsDateConstants.MS_PER_SECOND
import dev.kodakiko.media.JsDateConstants.YEARS_PER_ERA
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate

data class JsUtcFields(
    val year: Double,
    val monthIndex: Double,
    val day: Double,
    val hours: Double,
    val minutes: Double,
    val seconds: Double,
    val milliseconds: Double,
    val weekDay: Double,
)

object JsDate {
    fun timeClip(time: Double): Double =
        if (!time.isFinite() || abs(time) > JsDateConstants.MAX_TIME_VALUE) Double.NaN else truncate(time) + 0.0

    fun makeDay(year: Double, monthIndex: Double, date: Double): Double {
        if (!year.isFinite() || !monthIndex.isFinite() || !date.isFinite()) return Double.NaN
        val month = truncate(monthIndex)
        val yearShift = floor(month / MONTHS_PER_YEAR)
        val normalizedMonth = month - yearShift * MONTHS_PER_YEAR
        return daysFromCivil(truncate(year) + yearShift, normalizedMonth + 1, 1.0) + truncate(date) - 1
    }

    fun makeTime(hours: Double, minutes: Double, seconds: Double, milliseconds: Double): Double {
        if (!hours.isFinite() || !minutes.isFinite() || !seconds.isFinite() || !milliseconds.isFinite()) return Double.NaN
        return truncate(hours) * MS_PER_HOUR + truncate(minutes) * MS_PER_MINUTE + truncate(seconds) * MS_PER_SECOND + truncate(milliseconds)
    }

    fun makeDate(day: Double, time: Double): Double =
        if (!day.isFinite() || !time.isFinite()) Double.NaN else day * MS_PER_DAY + time

    fun utc(year: Double, monthIndex: Double, day: Double, hours: Double, minutes: Double, seconds: Double): Double {
        val whole = if (year.isNaN()) year else truncate(year)
        val fullYear = if (whole >= 0 && whole <= JsDateConstants.TWO_DIGIT_YEAR_MAX) JsDateConstants.TWO_DIGIT_YEAR_BASE + whole else whole
        return timeClip(makeDate(makeDay(fullYear, monthIndex, day), makeTime(hours, minutes, seconds, 0.0)))
    }

    fun fromUtcParts(year: Double, monthIndex: Double, day: Double, hours: Double, minutes: Double, seconds: Double, milliseconds: Double): Double =
        timeClip(makeDate(makeDay(year, monthIndex, day), makeTime(hours, minutes, seconds, milliseconds)))

    fun fields(time: Double): JsUtcFields? {
        if (time.isNaN()) return null
        val day = floor(time / MS_PER_DAY)
        var rest = time - day * MS_PER_DAY
        val (year, month, date) = civilFromDays(day)
        val hours = floor(rest / MS_PER_HOUR)
        rest -= hours * MS_PER_HOUR
        val minutes = floor(rest / MS_PER_MINUTE)
        rest -= minutes * MS_PER_MINUTE
        val seconds = floor(rest / MS_PER_SECOND)
        rest -= seconds * MS_PER_SECOND
        val weekDay = positiveRemainder(day + JsDateConstants.EPOCH_WEEKDAY, DAYS_PER_WEEK)
        return JsUtcFields(year, month - 1, date, hours, minutes, seconds, rest, weekDay)
    }

    fun toIsoString(time: Double): String {
        val parts = fields(timeClip(time)) ?: throw RangeError(JsDateConstants.INVALID_TIME_VALUE)
        return buildString {
            append(isoYear(parts.year))
            append(JsDateConstants.DATE_SEPARATOR).append(pad(parts.monthIndex + 1, JsDateConstants.TWO_DIGITS))
            append(JsDateConstants.DATE_SEPARATOR).append(pad(parts.day, JsDateConstants.TWO_DIGITS))
            append(JsDateConstants.DATE_TIME_SEPARATOR).append(pad(parts.hours, JsDateConstants.TWO_DIGITS))
            append(JsDateConstants.TIME_SEPARATOR).append(pad(parts.minutes, JsDateConstants.TWO_DIGITS))
            append(JsDateConstants.TIME_SEPARATOR).append(pad(parts.seconds, JsDateConstants.TWO_DIGITS))
            append(JsDateConstants.FRACTION_SEPARATOR).append(pad(parts.milliseconds, JsDateConstants.MILLISECOND_DIGITS))
            append(JsDateConstants.UTC_DESIGNATOR)
        }
    }

    fun parse(text: String): Double {
        val match = JsDateConstants.ISO_PATTERN.matchEntire(text) ?: return Double.NaN
        val groups = match.groupValues
        val yearText = groups[JsDateConstants.YEAR_GROUP]
        if (yearText == JsDateConstants.EXPANDED_YEAR_ZERO) return Double.NaN
        val month = groups[JsDateConstants.MONTH_GROUP].ifEmpty { null }?.toInt() ?: 1
        val day = groups[JsDateConstants.DAY_GROUP].ifEmpty { null }?.toInt() ?: 1
        val hours = groups[JsDateConstants.HOUR_GROUP].ifEmpty { null }?.toInt() ?: 0
        val minutes = groups[JsDateConstants.MINUTE_GROUP].ifEmpty { null }?.toInt() ?: 0
        val seconds = groups[JsDateConstants.SECOND_GROUP].ifEmpty { null }?.toInt() ?: 0
        val milliseconds = groups[JsDateConstants.FRACTION_GROUP].take(JsDateConstants.MILLISECOND_DIGITS)
            .padEnd(JsDateConstants.MILLISECOND_DIGITS, JsDateConstants.PAD).toInt()
        val offsetMinutes = offsetMinutesOf(groups[JsDateConstants.OFFSET_GROUP]) ?: return Double.NaN
        val valid = month in 1..JsDateConstants.MONTH_MAX &&
            day in 1..JsDateConstants.DAY_MAX &&
            minutes <= JsDateConstants.MINUTE_MAX &&
            seconds <= JsDateConstants.SECOND_MAX &&
            (hours < JsDateConstants.HOUR_MAX || (hours == JsDateConstants.HOUR_MAX && minutes == 0 && seconds == 0 && milliseconds == 0))
        if (!valid) return Double.NaN
        val local = makeDate(
            makeDay(yearText.toDouble(), (month - 1).toDouble(), day.toDouble()),
            makeTime(hours.toDouble(), minutes.toDouble(), seconds.toDouble(), milliseconds.toDouble()),
        )
        return timeClip(local - offsetMinutes * MS_PER_MINUTE)
    }

    private fun offsetMinutesOf(text: String): Double? {
        if (text.isEmpty() || text.equals(JsDateConstants.UTC_DESIGNATOR, ignoreCase = true)) return 0.0
        val hours = text.substring(1, JsDateConstants.OFFSET_HOURS_END).toInt()
        val minutes = text.substring(JsDateConstants.OFFSET_MINUTES_START).toInt()
        if (hours > JsDateConstants.OFFSET_HOUR_MAX || minutes > JsDateConstants.MINUTE_MAX) return null
        val total = (hours * MEDIA_MINUTES_PER_HOUR + minutes).toDouble()
        return if (text[0] == JsDateConstants.MINUS) -total else total
    }

    private fun isoYear(year: Double): String =
        if (year >= 0 && year <= JsDateConstants.ISO_YEAR_MAX) {
            pad(year, JsDateConstants.YEAR_DIGITS)
        } else {
            (if (year < 0) JsDateConstants.MINUS else JsDateConstants.PLUS) + pad(abs(year), JsDateConstants.EXPANDED_YEAR_DIGITS)
        }

    private fun pad(value: Double, digits: Int): String = value.toLong().toString().padStart(digits, JsDateConstants.PAD)

    private fun positiveRemainder(value: Double, divisor: Double): Double = value - floor(value / divisor) * divisor

    private fun daysFromCivil(year: Double, month: Double, day: Double): Double {
        val shiftedYear = if (month <= MARCH_BASED_MONTH_OFFSET) year - 1 else year
        val era = floor(shiftedYear / YEARS_PER_ERA)
        val yearOfEra = shiftedYear - era * YEARS_PER_ERA
        val marchMonth = if (month > MARCH_BASED_MONTH_OFFSET) month - MARCH_SHIFT_FORWARD else month + MARCH_SHIFT_BACK
        val dayOfYear = floor((MARCH_BASED_MONTH_DAYS * marchMonth + MARCH_BASED_MONTH_OFFSET) / MARCH_BASED_DAY_ROUNDING) + day - 1
        val dayOfEra = yearOfEra * DAYS_PER_YEAR + floor(yearOfEra / LEAP_CYCLE_YEARS) - floor(yearOfEra / CENTURY_YEARS) + dayOfYear
        return era * DAYS_PER_ERA + dayOfEra - ERA_DAY_OFFSET
    }

    private fun civilFromDays(days: Double): Triple<Double, Double, Double> {
        val shifted = days + ERA_DAY_OFFSET
        val era = floor(shifted / DAYS_PER_ERA)
        val dayOfEra = shifted - era * DAYS_PER_ERA
        val yearOfEra = floor(
            (dayOfEra - floor(dayOfEra / ERA_FOUR_YEAR_DAYS) + floor(dayOfEra / ERA_CENTURY_DAYS) - floor(dayOfEra / ERA_LAST_DAY)) / DAYS_PER_YEAR,
        )
        val dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + floor(yearOfEra / LEAP_CYCLE_YEARS) - floor(yearOfEra / CENTURY_YEARS))
        val marchMonth = floor((MARCH_BASED_DAY_ROUNDING * dayOfYear + MARCH_BASED_MONTH_OFFSET) / MARCH_BASED_MONTH_DAYS)
        val day = dayOfYear - floor((MARCH_BASED_MONTH_DAYS * marchMonth + MARCH_BASED_MONTH_OFFSET) / MARCH_BASED_DAY_ROUNDING) + 1
        val month = if (marchMonth < MARCH_SHIFT_LIMIT) marchMonth + MARCH_SHIFT_FORWARD else marchMonth - MARCH_SHIFT_BACK
        val year = yearOfEra + era * YEARS_PER_ERA
        return Triple(if (month <= MARCH_BASED_MONTH_OFFSET) year + 1 else year, month, day)
    }
}
