package dev.kodakiko.service.entries

import dev.kodakiko.js.jsStringToNumber
import dev.kodakiko.media.EXIF_DATE_PATTERN
import dev.kodakiko.media.EXIF_OFFSET_PATTERN
import dev.kodakiko.media.GPS_EAST
import dev.kodakiko.media.GPS_NORTH
import dev.kodakiko.media.GPS_SOUTH
import dev.kodakiko.media.GPS_WEST
import dev.kodakiko.media.GeoPoint
import dev.kodakiko.media.ImageMetadata
import dev.kodakiko.media.LATITUDE_LIMIT
import dev.kodakiko.media.LONGITUDE_LIMIT
import dev.kodakiko.media.MEDIA_MINUTES_PER_HOUR
import dev.kodakiko.media.MEDIA_SECONDS_PER_HOUR
import dev.kodakiko.media.ORIENTATION_MAX
import dev.kodakiko.media.ORIENTATION_MIN
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

private fun stringOf(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

private fun numberOf(value: JsonElement?): Double? {
    val primitive = (value as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString } ?: return null
    if (primitive.content == PickerExifKeys.JSON_TRUE || primitive.content == PickerExifKeys.JSON_FALSE) return null
    return primitive.content.toDoubleOrNull()
}

private fun matchingString(value: JsonElement?, pattern: Regex): String? = stringOf(value)?.takeIf { pattern.matches(it) }

private fun fractionValue(part: String): Double {
    val pieces = part.split(PickerExifKeys.FRACTION_SEPARATOR).map(::jsStringToNumber)
    val numerator = pieces[0]
    val denominator = pieces.getOrNull(1) ?: return Double.NaN
    if (denominator == 0.0) return Double.NaN
    return numerator / denominator
}

private fun degreesOf(value: JsonElement?): Double? {
    numberOf(value)?.let { return if (it.isFinite()) abs(it) else null }
    val text = stringOf(value) ?: return null
    val parts = text.split(PickerExifKeys.PART_SEPARATOR)
    if (parts.size != PickerExifKeys.COORDINATE_PARTS) return null
    val (degrees, minutes, seconds) = parts.map(::fractionValue)
    val total = degrees + minutes / MEDIA_MINUTES_PER_HOUR + seconds / MEDIA_SECONDS_PER_HOUR
    return if (total.isFinite()) total else null
}

private fun signed(value: JsonElement?, ref: JsonElement?, positive: String, negative: String): Double? {
    val degrees = degreesOf(value) ?: return null
    val reference = stringOf(ref)
    if (reference == positive) return degrees
    return if (reference == negative) 0 - degrees else null
}

private fun gpsOf(exif: JsonObject): GeoPoint? {
    val latitude = signed(exif[PickerExifKeys.GPS_LATITUDE], exif[PickerExifKeys.GPS_LATITUDE_REF], GPS_NORTH, GPS_SOUTH) ?: return null
    val longitude = signed(exif[PickerExifKeys.GPS_LONGITUDE], exif[PickerExifKeys.GPS_LONGITUDE_REF], GPS_EAST, GPS_WEST) ?: return null
    if (abs(latitude) > LATITUDE_LIMIT || abs(longitude) > LONGITUDE_LIMIT) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return GeoPoint(latitude, longitude)
}

private fun orientationOf(value: JsonElement?): Int? {
    val number = numberOf(value) ?: return null
    val isInteger = number.isFinite() && floor(number) == number
    return if (isInteger && number >= ORIENTATION_MIN && number <= ORIENTATION_MAX) number.toInt() else null
}

fun pickerExifToMetadata(exif: JsonObject): ImageMetadata =
    ImageMetadata(
        dateTimeOriginal = matchingString(exif[PickerExifKeys.DATE_TIME_ORIGINAL], EXIF_DATE_PATTERN),
        offsetTimeOriginal = matchingString(exif[PickerExifKeys.OFFSET_TIME_ORIGINAL], EXIF_OFFSET_PATTERN),
        gps = gpsOf(exif),
        orientation = orientationOf(exif[PickerExifKeys.ORIENTATION]),
        malformed = false,
    )
