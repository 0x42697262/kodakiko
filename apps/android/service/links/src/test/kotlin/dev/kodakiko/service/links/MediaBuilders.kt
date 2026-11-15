package dev.kodakiko.service.links

import dev.kodakiko.js.JsDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

private const val BUILDER_BYTE_MASK = 0xff
private const val BUILDER_TIFF_MAGIC = 42
private const val BUILDER_TIFF_HEADER_BYTES = 8
private const val BUILDER_IFD_COUNT_BYTES = 2
private const val BUILDER_IFD_ENTRY_BYTES = 12
private const val BUILDER_IFD_NEXT_BYTES = 4
private const val BUILDER_INLINE_VALUE_BYTES = 4
private const val BUILDER_RATIONAL_SECONDS_DENOMINATOR = 10_000L
private const val BUILDER_MINUTES_PER_DEGREE = 60.0
private const val BUILDER_TYPE_ASCII = 2
private const val BUILDER_TYPE_SHORT = 3
private const val BUILDER_TYPE_LONG = 4
private const val BUILDER_TYPE_RATIONAL = 5
private const val BUILDER_JPEG_MARKER_APP0 = 0xe0
private const val BUILDER_JPEG_MARKER_APP1 = 0xe1
private const val BUILDER_JPEG_SEGMENT_LENGTH_BYTES = 2
private const val BUILDER_SCAN_PATTERN_MODULO = 251
private const val BUILDER_SCAN_PATTERN_STEP = 7
private const val BUILDER_EXIF_IDENTIFIER = "Exif\u0000\u0000"
private const val BUILDER_BOX_HEADER_BYTES = 8
private const val BUILDER_MVHD_V0_PAYLOAD_BYTES = 100
private const val BUILDER_QUICKTIME_LANGUAGE_CODE = 0x15c7L
private const val BUILDER_HDLR_RESERVED_BYTES = 12
private const val BUILDER_FIXED_ONE = 0x10000L
private const val BUILDER_FIXED_MINUS_ONE = 0x1_0000_0000L - BUILDER_FIXED_ONE
private const val BUILDER_MATRIX_W = 0x4000_0000L
private const val BUILDER_TKHD_V0_PREFIX_BYTES = 36
private const val BUILDER_MS_PER_SECOND = 1000.0

private const val BUILDER_TAG_ORIENTATION = 0x0112
private const val BUILDER_TAG_EXIF_POINTER = 0x8769
private const val BUILDER_TAG_GPS_POINTER = 0x8825
private const val BUILDER_TAG_DATE_TIME_ORIGINAL = 0x9003
private const val BUILDER_TAG_OFFSET_TIME_ORIGINAL = 0x9011
private const val BUILDER_TAG_GPS_LATITUDE_REF = 1
private const val BUILDER_TAG_GPS_LATITUDE = 2
private const val BUILDER_TAG_GPS_LONGITUDE_REF = 3
private const val BUILDER_TAG_GPS_LONGITUDE = 4

private val BUILDER_SOS_SEGMENT = listOf(0xff, 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00)
private val BUILDER_JPEG_SOI = listOf(0xff, 0xd8)
private val BUILDER_JPEG_EOI = listOf(0xff, 0xd9)
private val BUILDER_LITTLE_ENDIAN_MARK = listOf(0x49, 0x49)
private val BUILDER_BIG_ENDIAN_MARK = listOf(0x4d, 0x4d)
private val BUILDER_XYZ_TYPE = byteArrayOf(0xa9.toByte(), 0x78, 0x79, 0x7a)

data class TestGps(val latitude: Double, val longitude: Double)

enum class TestRotation(val degrees: Int) {
    DEGREES_0(0),
    DEGREES_90(90),
    DEGREES_180(180),
    DEGREES_270(270),
}

private enum class IfdPointer { EXIF, GPS }

private class IfdEntry(val tag: Int, val type: Int, val count: Int, val data: List<Int>, val pointer: IfdPointer? = null)

private class ExternalData(var next: Int, val bytes: MutableList<Int> = mutableListOf())

fun asciiBytes(text: String): ByteArray = ByteArray(text.length) { (text[it].code and BUILDER_BYTE_MASK).toByte() }

fun joinBytes(vararg parts: ByteArray): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

private fun List<Int>.asBytes(): ByteArray = ByteArray(size) { this[it].toByte() }

private fun u16(value: Int, littleEndian: Boolean): List<Int> {
    val bytes = listOf((value ushr 8) and BUILDER_BYTE_MASK, value and BUILDER_BYTE_MASK)
    return if (littleEndian) bytes.reversed() else bytes
}

private fun u32(value: Long, littleEndian: Boolean): List<Int> {
    val bytes = listOf(24, 16, 8, 0).map { ((value ushr it) and BUILDER_BYTE_MASK.toLong()).toInt() }
    return if (littleEndian) bytes.reversed() else bytes
}

private fun u16be(value: Int): ByteArray = u16(value, false).asBytes()

private fun u32be(value: Long): ByteArray = u32(value, false).asBytes()

private fun asciiEntry(tag: Int, text: String): IfdEntry {
    val data = asciiBytes(text).map { it.toInt() and BUILDER_BYTE_MASK } + 0
    return IfdEntry(tag, BUILDER_TYPE_ASCII, data.size, data)
}

private fun shortEntry(tag: Int, value: Int, littleEndian: Boolean): IfdEntry =
    IfdEntry(tag, BUILDER_TYPE_SHORT, 1, u16(value, littleEndian) + listOf(0, 0))

private fun rationalEntry(tag: Int, parts: List<Pair<Long, Long>>, littleEndian: Boolean): IfdEntry =
    IfdEntry(tag, BUILDER_TYPE_RATIONAL, parts.size, parts.flatMap { (numerator, denominator) -> u32(numerator, littleEndian) + u32(denominator, littleEndian) })

private fun degreesMinutesSeconds(value: Double): List<Pair<Long, Long>> {
    val absolute = abs(value)
    val degrees = floor(absolute)
    val minutesWhole = (absolute - degrees) * BUILDER_MINUTES_PER_DEGREE
    val minutes = floor(minutesWhole)
    val seconds = ((minutesWhole - minutes) * BUILDER_MINUTES_PER_DEGREE * BUILDER_RATIONAL_SECONDS_DENOMINATOR).roundToLong()
    return listOf(degrees.toLong() to 1L, minutes.toLong() to 1L, seconds to BUILDER_RATIONAL_SECONDS_DENOMINATOR)
}

private fun gpsEntries(gps: TestGps, littleEndian: Boolean): List<IfdEntry> =
    listOf(
        asciiEntry(BUILDER_TAG_GPS_LATITUDE_REF, if (gps.latitude < 0) "S" else "N"),
        rationalEntry(BUILDER_TAG_GPS_LATITUDE, degreesMinutesSeconds(gps.latitude), littleEndian),
        asciiEntry(BUILDER_TAG_GPS_LONGITUDE_REF, if (gps.longitude < 0) "W" else "E"),
        rationalEntry(BUILDER_TAG_GPS_LONGITUDE, degreesMinutesSeconds(gps.longitude), littleEndian),
    )

private fun ifdByteLength(entries: List<IfdEntry>, force: Boolean = false): Int =
    if (entries.isEmpty() && !force) 0 else BUILDER_IFD_COUNT_BYTES + BUILDER_IFD_ENTRY_BYTES * entries.size + BUILDER_IFD_NEXT_BYTES

private fun encodeIfd(entries: List<IfdEntry>, littleEndian: Boolean, external: ExternalData, pointers: Map<IfdPointer, Int>, force: Boolean = false): List<Int> {
    if (entries.isEmpty() && !force) return emptyList()
    val bytes = u16(entries.size, littleEndian).toMutableList()
    for (entry in entries) {
        bytes += u16(entry.tag, littleEndian) + u16(entry.type, littleEndian) + u32(entry.count.toLong(), littleEndian)
        val pointer = entry.pointer
        if (pointer != null) {
            bytes += u32(pointers.getValue(pointer).toLong(), littleEndian)
        } else if (entry.data.size <= BUILDER_INLINE_VALUE_BYTES) {
            bytes += entry.data + List(BUILDER_INLINE_VALUE_BYTES - entry.data.size) { 0 }
        } else {
            val paddedLength = entry.data.size + (entry.data.size % 2)
            bytes += u32(external.next.toLong(), littleEndian)
            external.bytes += entry.data
            if (paddedLength > entry.data.size) external.bytes += 0
            external.next += paddedLength
        }
    }
    bytes += u32(0, littleEndian)
    return bytes
}

fun buildTiff(
    dateTimeOriginal: String? = null,
    offsetTimeOriginal: String? = null,
    gps: TestGps? = null,
    orientation: Int? = null,
    littleEndian: Boolean = true,
): ByteArray {
    val ifd0 = mutableListOf<IfdEntry>()
    val exif = mutableListOf<IfdEntry>()
    val gpsIfd = mutableListOf<IfdEntry>()
    if (orientation != null) ifd0 += shortEntry(BUILDER_TAG_ORIENTATION, orientation, littleEndian)
    if (dateTimeOriginal != null) exif += asciiEntry(BUILDER_TAG_DATE_TIME_ORIGINAL, dateTimeOriginal)
    if (offsetTimeOriginal != null) exif += asciiEntry(BUILDER_TAG_OFFSET_TIME_ORIGINAL, offsetTimeOriginal)
    if (gps != null) gpsIfd += gpsEntries(gps, littleEndian)
    if (exif.isNotEmpty()) ifd0 += IfdEntry(BUILDER_TAG_EXIF_POINTER, BUILDER_TYPE_LONG, 1, emptyList(), IfdPointer.EXIF)
    if (gpsIfd.isNotEmpty()) ifd0 += IfdEntry(BUILDER_TAG_GPS_POINTER, BUILDER_TYPE_LONG, 1, emptyList(), IfdPointer.GPS)
    ifd0.sortBy { it.tag }
    val exifOffset = BUILDER_TIFF_HEADER_BYTES + ifdByteLength(ifd0, true)
    val gpsOffset = exifOffset + ifdByteLength(exif)
    val external = ExternalData(gpsOffset + ifdByteLength(gpsIfd))
    val pointers = mapOf(IfdPointer.EXIF to exifOffset, IfdPointer.GPS to gpsOffset)
    val encodedIfd0 = encodeIfd(ifd0, littleEndian, external, pointers, true)
    val encodedExif = encodeIfd(exif, littleEndian, external, pointers)
    val encodedGps = encodeIfd(gpsIfd, littleEndian, external, pointers)
    val order = if (littleEndian) BUILDER_LITTLE_ENDIAN_MARK else BUILDER_BIG_ENDIAN_MARK
    return (
        order + u16(BUILDER_TIFF_MAGIC, littleEndian) + u32(BUILDER_TIFF_HEADER_BYTES.toLong(), littleEndian) +
            encodedIfd0 + encodedExif + encodedGps + external.bytes
        ).asBytes()
}

fun jpegSegment(marker: Int, payload: ByteArray): ByteArray =
    joinBytes(listOf(BUILDER_BYTE_MASK, marker).asBytes(), u16be(payload.size + BUILDER_JPEG_SEGMENT_LENGTH_BYTES), payload)

fun jfifApp0(): ByteArray =
    jpegSegment(BUILDER_JPEG_MARKER_APP0, joinBytes(asciiBytes("JFIF\u0000"), listOf(1, 1, 0, 0, 1, 0, 1, 0, 0).asBytes()))

fun exifApp1(tiff: ByteArray): ByteArray = jpegSegment(BUILDER_JPEG_MARKER_APP1, joinBytes(asciiBytes(BUILDER_EXIF_IDENTIFIER), tiff))

fun buildJpeg(segments: List<ByteArray> = emptyList(), scanBytes: Int = 0): ByteArray {
    val scan = ByteArray(scanBytes) { ((it.toLong() * BUILDER_SCAN_PATTERN_STEP) % BUILDER_SCAN_PATTERN_MODULO).toInt().toByte() }
    return joinBytes(BUILDER_JPEG_SOI.asBytes(), *segments.toTypedArray(), BUILDER_SOS_SEGMENT.asBytes(), scan, BUILDER_JPEG_EOI.asBytes())
}

fun isoBox(type: ByteArray, payload: ByteArray): ByteArray = joinBytes(u32be((payload.size + BUILDER_BOX_HEADER_BYTES).toLong()), type, payload)

fun isoBox(type: String, payload: ByteArray): ByteArray = isoBox(asciiBytes(type), payload)

fun ftypBox(major: String, compatible: List<String> = emptyList()): ByteArray =
    isoBox("ftyp", joinBytes(asciiBytes(major), u32be(0), *compatible.map(::asciiBytes).toTypedArray()))

fun mvhdBox(creationSeconds1904: Long): ByteArray =
    isoBox("mvhd", joinBytes(byteArrayOf(0, 0, 0, 0), u32be(creationSeconds1904), ByteArray(BUILDER_MVHD_V0_PAYLOAD_BYTES - 8)))

fun xyzBox(text: String): ByteArray = isoBox(BUILDER_XYZ_TYPE, joinBytes(u16be(text.length), u16be(BUILDER_QUICKTIME_LANGUAGE_CODE.toInt()), asciiBytes(text)))

fun udtaBox(vararg children: ByteArray): ByteArray = isoBox("udta", joinBytes(*children))

private fun hdlrBox(handlerType: String): ByteArray =
    isoBox("hdlr", joinBytes(byteArrayOf(0, 0, 0, 0), u32be(0), asciiBytes(handlerType), ByteArray(BUILDER_HDLR_RESERVED_BYTES), byteArrayOf(0)))

fun rotationMatrix(rotation: TestRotation): List<Long> {
    val (a, b, c, d) = when (rotation) {
        TestRotation.DEGREES_0 -> listOf(BUILDER_FIXED_ONE, 0L, 0L, BUILDER_FIXED_ONE)
        TestRotation.DEGREES_90 -> listOf(0L, BUILDER_FIXED_ONE, BUILDER_FIXED_MINUS_ONE, 0L)
        TestRotation.DEGREES_180 -> listOf(BUILDER_FIXED_MINUS_ONE, 0L, 0L, BUILDER_FIXED_MINUS_ONE)
        TestRotation.DEGREES_270 -> listOf(0L, BUILDER_FIXED_MINUS_ONE, BUILDER_FIXED_ONE, 0L)
    }
    return listOf(a, b, 0L, c, d, 0L, 0L, 0L, BUILDER_MATRIX_W)
}

fun tkhdBox(matrix: List<Long>): ByteArray =
    isoBox(
        "tkhd",
        joinBytes(byteArrayOf(0, 0, 0, 3), ByteArray(BUILDER_TKHD_V0_PREFIX_BYTES), *matrix.map(::u32be).toTypedArray(), u32be(0), u32be(0)),
    )

fun trakBox(handlerType: String, header: ByteArray): ByteArray =
    isoBox("trak", joinBytes(header, isoBox("mdia", joinBytes(isoBox("mdhd", ByteArray(24)), hdlrBox(handlerType)))))

fun moovBox(vararg children: ByteArray): ByteArray = isoBox("moov", joinBytes(*children))

fun mp4Seconds(isoUtc: String, epochOffsetSeconds: Long): Long = (JsDate.parse(isoUtc) / BUILDER_MS_PER_SECOND).toLong() + epochOffsetSeconds
