package dev.kodakiko.media

import dev.kodakiko.js.TypeError
import kotlin.math.abs

data class GeoPoint(val latitude: Double, val longitude: Double)

data class ImageMetadata(
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val gps: GeoPoint?,
    val orientation: Int?,
    val malformed: Boolean,
)

private class MutableImageMetadata(var malformed: Boolean = false) {
    var dateTimeOriginal: String? = null
    var offsetTimeOriginal: String? = null
    var gps: GeoPoint? = null
    var orientation: Int? = null

    fun freeze(): ImageMetadata = ImageMetadata(dateTimeOriginal, offsetTimeOriginal, gps, orientation, malformed)
}

private class IfdEntry(val tag: Long, val type: Long, val count: Long, val valueOffset: Long)

private class TiffContext(val bytes: ByteArray, val little: Boolean) {
    val visited = mutableSetOf<Long>()
}

private fun emptyMetadata(malformed: Boolean = false): ImageMetadata = MutableImageMetadata(malformed).freeze()

private fun readIfd(context: TiffContext, offset: Long): List<IfdEntry> {
    if (!context.visited.add(offset)) return emptyList()
    val count = readUint16(context.bytes, offset, context.little)
    val limit = minOf(count, EXIF_MAX_IFD_ENTRIES.toLong())
    return (0 until limit).map { index ->
        val base = offset + IFD_COUNT_BYTES + index * IFD_ENTRY_BYTES
        IfdEntry(
            tag = readUint16(context.bytes, base, context.little),
            type = readUint16(context.bytes, base + IFD_ENTRY_TYPE_OFFSET, context.little),
            count = readUint32(context.bytes, base + IFD_ENTRY_COUNT_OFFSET, context.little),
            valueOffset = base + IFD_ENTRY_VALUE_OFFSET,
        )
    }
}

private fun entryFor(entries: List<IfdEntry>, tag: Long): IfdEntry? = entries.firstOrNull { it.tag == tag }

private fun dataOffset(context: TiffContext, entry: IfdEntry, byteLength: Long): Long =
    if (byteLength <= IFD_INLINE_VALUE_BYTES) entry.valueOffset else readUint32(context.bytes, entry.valueOffset, context.little)

private fun pointerOf(context: TiffContext, entries: List<IfdEntry>, tag: Long): Long? {
    val entry = entryFor(entries, tag)
    if (entry == null || entry.type != TIFF_TYPE_LONG) return null
    return readUint32(context.bytes, entry.valueOffset, context.little)
}

private fun orientationOf(context: TiffContext, entries: List<IfdEntry>): Int? {
    val entry = entryFor(entries, TAG_ORIENTATION)
    if (entry == null || entry.type != TIFF_TYPE_SHORT) return null
    val value = readUint16(context.bytes, entry.valueOffset, context.little)
    return if (value >= ORIENTATION_MIN && value <= ORIENTATION_MAX) value.toInt() else null
}

private fun asciiOf(context: TiffContext, entries: List<IfdEntry>, tag: Long): String? {
    val entry = entryFor(entries, tag)
    if (entry == null || entry.type != TIFF_TYPE_ASCII || entry.count > EXIF_ASCII_MAX_BYTES) return null
    val offset = dataOffset(context, entry, entry.count)
    return readAscii(context.bytes, offset, entry.count).trimEnd(NUL_CHAR)
}

private fun coordinateOf(context: TiffContext, entries: List<IfdEntry>, tag: Long): Double? {
    val entry = entryFor(entries, tag)
    if (entry == null || entry.type != TIFF_TYPE_RATIONAL || entry.count != GPS_COORDINATE_PARTS.toLong()) return null
    val offset = readUint32(context.bytes, entry.valueOffset, context.little)
    val (degrees, minutes, seconds) = (0 until GPS_COORDINATE_PARTS).map { index ->
        val base = offset + index * RATIONAL_BYTES
        val numerator = readUint32(context.bytes, base, context.little)
        val denominator = readUint32(context.bytes, base + RATIONAL_DENOMINATOR_OFFSET, context.little)
        if (denominator == 0L) Double.NaN else numerator.toDouble() / denominator.toDouble()
    }
    return degrees + minutes / MEDIA_MINUTES_PER_HOUR + seconds / MEDIA_SECONDS_PER_HOUR
}

private fun signedBy(value: Double?, ref: String?, positive: String, negative: String): Double? {
    if (value == null || ref == null) return null
    if (ref == positive) return value
    return if (ref == negative) 0 - value else null
}

private fun usablePoint(latitude: Double?, longitude: Double?): GeoPoint? {
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (abs(latitude) > LATITUDE_LIMIT || abs(longitude) > LONGITUDE_LIMIT) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return GeoPoint(latitude, longitude)
}

private fun readGps(context: TiffContext, entries: List<IfdEntry>): GeoPoint? {
    val latitude = signedBy(
        coordinateOf(context, entries, TAG_GPS_LATITUDE),
        asciiOf(context, entries, TAG_GPS_LATITUDE_REF),
        GPS_NORTH,
        GPS_SOUTH,
    )
    val longitude = signedBy(
        coordinateOf(context, entries, TAG_GPS_LONGITUDE),
        asciiOf(context, entries, TAG_GPS_LONGITUDE_REF),
        GPS_EAST,
        GPS_WEST,
    )
    return usablePoint(latitude, longitude)
}

private fun matching(text: String?, pattern: Regex): String? = if (text != null && pattern.matches(text)) text else null

private fun readTiff(bytes: ByteArray, found: MutableImageMetadata) {
    val mark = readAscii(bytes, TIFF_ORDER_OFFSET.toLong(), TIFF_ORDER_MARK_BYTES.toLong())
    if (mark != TIFF_LITTLE_ENDIAN_MARK && mark != TIFF_BIG_ENDIAN_MARK) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
    val little = mark == TIFF_LITTLE_ENDIAN_MARK
    if (readUint16(bytes, TIFF_MAGIC_OFFSET.toLong(), little) != TIFF_MAGIC) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
    val context = TiffContext(bytes, little)
    val ifd0 = readIfd(context, readUint32(bytes, TIFF_IFD0_POINTER_OFFSET.toLong(), little))
    found.orientation = orientationOf(context, ifd0)
    val exifPointer = pointerOf(context, ifd0, TAG_EXIF_POINTER)
    if (exifPointer != null) {
        val exif = readIfd(context, exifPointer)
        found.dateTimeOriginal = matching(asciiOf(context, exif, TAG_DATE_TIME_ORIGINAL), EXIF_DATE_PATTERN)
        found.offsetTimeOriginal = matching(asciiOf(context, exif, TAG_OFFSET_TIME_ORIGINAL), EXIF_OFFSET_PATTERN)
    }
    val gpsPointer = pointerOf(context, ifd0, TAG_GPS_POINTER)
    if (gpsPointer != null) found.gps = readGps(context, readIfd(context, gpsPointer))
}

fun parseTiffExif(tiff: ByteArray): ImageMetadata {
    val found = MutableImageMetadata()
    try {
        readTiff(tiff, found)
    } catch (error: Exception) {
        found.malformed = true
    }
    return found.freeze()
}

private fun isStandaloneMarker(marker: Long): Boolean =
    marker == JPEG_MARKER_TEM || (marker >= JPEG_MARKER_RST_MIN && marker <= JPEG_MARKER_RST_MAX)

private fun locateJpeg(bytes: ByteArray): ByteArray? {
    if (readUint8(bytes, 0) != JPEG_MARKER_PREFIX || readUint8(bytes, 1) != JPEG_SOI_SECOND_BYTE) {
        throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
    }
    var position = JPEG_SOI_BYTES.toLong()
    while (position < bytes.size) {
        if (readUint8(bytes, position) != JPEG_MARKER_PREFIX) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
        val marker = readUint8(bytes, position + 1)
        if (marker == JPEG_MARKER_SOS || marker == JPEG_MARKER_EOI) return null
        if (isStandaloneMarker(marker)) {
            position += JPEG_MARKER_BYTES
            continue
        }
        val length = readUint16(bytes, position + JPEG_MARKER_BYTES)
        if (length < JPEG_LENGTH_FIELD_BYTES) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
        val end = position + JPEG_MARKER_BYTES + length
        val payload = sliceRange(bytes, position + JPEG_SEGMENT_HEADER_BYTES, end)
        if (marker == JPEG_MARKER_APP1 && startsWithAscii(payload, 0, EXIF_IDENTIFIER)) {
            return subarray(payload, EXIF_IDENTIFIER.length.toLong())
        }
        position = end
    }
    return null
}

private fun locatePng(bytes: ByteArray): ByteArray? {
    if (!startsWithBytes(bytes, 0, PNG_SIGNATURE)) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
    var position = PNG_SIGNATURE_BYTES.toLong()
    while (position < bytes.size) {
        val length = readUint32(bytes, position)
        val type = readAscii(bytes, position + PNG_CHUNK_TYPE_OFFSET, PNG_CHUNK_TYPE_BYTES.toLong())
        val dataStart = position + PNG_CHUNK_HEADER_BYTES
        val dataEnd = dataStart + length
        val data = sliceRange(bytes, dataStart, dataEnd)
        if (type == PNG_EXIF_CHUNK) return data
        if (type == PNG_END_CHUNK) return null
        position = dataEnd + PNG_CRC_BYTES
    }
    return null
}

private fun locateWebp(bytes: ByteArray): ByteArray? {
    if (!startsWithAscii(bytes, 0, RIFF_TAG) || !startsWithAscii(bytes, RIFF_WEBP_TAG_OFFSET.toLong(), RIFF_WEBP_TAG)) {
        throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
    }
    var position = RIFF_HEADER_BYTES.toLong()
    while (position < bytes.size) {
        val type = readAscii(bytes, position, PNG_CHUNK_TYPE_BYTES.toLong())
        val size = readUint32(bytes, position + RIFF_CHUNK_SIZE_OFFSET, true)
        val dataEnd = position + RIFF_CHUNK_HEADER_BYTES + size
        val data = sliceRange(bytes, position + RIFF_CHUNK_HEADER_BYTES, dataEnd)
        if (type == WEBP_EXIF_CHUNK) {
            return if (startsWithAscii(data, 0, EXIF_IDENTIFIER)) subarray(data, EXIF_IDENTIFIER.length.toLong()) else data
        }
        position = dataEnd + size % RIFF_PAD_ALIGNMENT
    }
    return null
}

private fun locatorFor(container: MediaContainer): (ByteArray) -> ByteArray? =
    when (container) {
        MediaContainer.JPEG -> ::locateJpeg
        MediaContainer.PNG -> ::locatePng
        MediaContainer.WEBP -> ::locateWebp
        else -> throw TypeError(MediaText.NOT_AN_IMAGE_CONTAINER)
    }

fun readImageMetadata(source: ByteSource, container: MediaContainer): ImageMetadata {
    var limited = false
    return try {
        val window = source.read(0, minOf(source.size, EXIF_SCAN_MAX_BYTES.toLong()))
        limited = source.size > window.size
        val tiff = locatorFor(container)(window)
        if (tiff == null) emptyMetadata() else parseTiffExif(tiff)
    } catch (error: Exception) {
        emptyMetadata(!(error is MediaRangeError && limited))
    }
}

fun hasExifSegment(bytes: ByteArray): Boolean =
    try {
        locateJpeg(bytes) != null
    } catch (error: Exception) {
        true
    }
