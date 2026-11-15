package dev.kodakiko.media

import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsTrim
import kotlin.math.abs

data class VideoMetadata(
    val creationUtc: String?,
    val location: GeoPoint?,
    val rotationDegrees: VideoRotation,
    val malformed: Boolean,
)

private class MutableVideoMetadata {
    var creationUtc: String? = null
    var location: GeoPoint? = null
    var rotationDegrees: VideoRotation = VideoRotation.DEGREES_0
    var malformed: Boolean = false

    fun freeze(): VideoMetadata = VideoMetadata(creationUtc, location, rotationDegrees, malformed)
}

private class BoxHeader(val type: String, val typeCode: Long, val headerSize: Int, val size: Long)

private class ChildBox(val type: String, val typeCode: Long, val payload: ByteArray)

private fun parseBoxHeader(peek: ByteArray, available: Long): BoxHeader {
    if (peek.size < BOX_HEADER_BYTES) throw MediaRangeError(MediaText.OUT_OF_RANGE)
    val declared = readUint32(peek, 0)
    val type = readAscii(peek, BOX_TYPE_OFFSET.toLong(), BOX_TYPE_BYTES.toLong())
    val typeCode = readUint32(peek, BOX_TYPE_OFFSET.toLong())
    val large = declared == BOX_LARGE_SIZE_MARKER
    val headerSize = if (large) BOX_LARGE_HEADER_BYTES else BOX_HEADER_BYTES
    var size = declared
    if (large) size = readUint64(peek, BOX_LARGE_SIZE_OFFSET.toLong())
    if (declared == BOX_TO_END_MARKER) size = available
    if (size < headerSize || size > available) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
    return BoxHeader(type, typeCode, headerSize, size)
}

private fun childBoxes(bytes: ByteArray): Sequence<ChildBox> = sequence {
    var position = 0L
    while (position < bytes.size) {
        val peek = subarray(bytes, position, position + BOX_PEEK_BYTES)
        val header = parseBoxHeader(peek, bytes.size - position)
        yield(ChildBox(header.type, header.typeCode, subarray(bytes, position + header.headerSize, position + header.size)))
        position += header.size
    }
}

fun parseIso6709(text: String): GeoPoint? {
    if (text.length > ISO6709_MAX_BYTES) return null
    val match = ISO6709_PATTERN.matchEntire(jsTrim(text.trimEnd(NUL_CHAR))) ?: return null
    val latitude = match.groupValues[ISO6709_LATITUDE_GROUP].toDouble()
    val longitude = match.groupValues[ISO6709_LONGITUDE_GROUP].toDouble()
    if (abs(latitude) > LATITUDE_LIMIT || abs(longitude) > LONGITUDE_LIMIT) return null
    return GeoPoint(latitude, longitude)
}

private fun creationTime(mvhdPayload: ByteArray): String? {
    val version = readUint8(mvhdPayload, 0)
    val seconds = if (version == MVHD_VERSION_1) {
        readUint64(mvhdPayload, MVHD_CREATION_OFFSET.toLong())
    } else {
        readUint32(mvhdPayload, MVHD_CREATION_OFFSET.toLong())
    }
    if (seconds == 0L) return null
    val time = JsDate.timeClip(((seconds - MP4_EPOCH_OFFSET_SECONDS) * MEDIA_MS_PER_SECOND).toDouble())
    val fields = JsDate.fields(time) ?: return null
    if (fields.year > MAX_ISO_YEAR) return null
    return JsDate.toIsoString(time)
}

private fun xyzLocation(payload: ByteArray): GeoPoint? {
    val length = readUint16(payload, 0)
    if (length > ISO6709_MAX_BYTES) return null
    val start = (XYZ_LENGTH_BYTES + XYZ_LANGUAGE_BYTES).toLong()
    val available = maxOf(0L, payload.size - start)
    return parseIso6709(readAscii(payload, start, minOf(length, available)))
}

private fun keyNames(payload: ByteArray): List<String> {
    val count = readUint32(payload, KEYS_COUNT_OFFSET.toLong())
    val names = mutableListOf<String>()
    var position = KEYS_FIRST_ENTRY_OFFSET.toLong()
    var index = 0L
    while (index < count && position < payload.size) {
        val size = readUint32(payload, position)
        val nameStart = position + KEYS_ENTRY_SIZE_BYTES + KEYS_ENTRY_NAMESPACE_BYTES
        if (size < nameStart - position) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
        val nameBytes = position + size - nameStart
        names.add(if (nameBytes > KEYS_NAME_MAX_BYTES) "" else readAscii(payload, nameStart, nameBytes))
        position += size
        index += 1
    }
    return names
}

private fun itemText(itemPayload: ByteArray): String? {
    for (child in childBoxes(itemPayload)) {
        if (child.type == BOX_DATA && child.payload.size >= DATA_BOX_VALUE_OFFSET) {
            val length = child.payload.size - DATA_BOX_VALUE_OFFSET
            if (length > ISO6709_MAX_BYTES) return null
            return readAscii(child.payload, DATA_BOX_VALUE_OFFSET.toLong(), length.toLong())
        }
    }
    return null
}

private fun appleLocation(metaPayload: ByteArray): GeoPoint? {
    val fullBox = metaPayload.size >= FULL_BOX_HEADER_BYTES && readUint32(metaPayload, 0) == 0L
    val children = childBoxes(subarray(metaPayload, if (fullBox) FULL_BOX_HEADER_BYTES.toLong() else 0L)).toList()
    val keys = children.firstOrNull { it.type == BOX_KEYS }
    val items = children.firstOrNull { it.type == BOX_ILST }
    if (keys == null || items == null) return null
    val names = keyNames(keys.payload)
    for (item in childBoxes(items.payload)) {
        if (nameAt(names, item.typeCode) != APPLE_LOCATION_KEY) continue
        val text = itemText(item.payload)
        val point = if (text == null) null else parseIso6709(text)
        if (point != null) return point
    }
    return null
}

private fun nameAt(names: List<String>, typeCode: Long): String? {
    val index = typeCode - 1
    return if (index >= 0 && index < names.size) names[index.toInt()] else null
}

private fun signedCell(payload: ByteArray, offset: Long): Long {
    val value = readUint32(payload, offset)
    return if (value > INT32_MAX) value - UINT32_RANGE else value
}

private fun trackRotation(tkhdPayload: ByteArray): VideoRotation {
    val base = if (readUint8(tkhdPayload, 0) == TKHD_VERSION_1) TKHD_MATRIX_OFFSET_V1 else TKHD_MATRIX_OFFSET_V0
    val cells = listOf(MATRIX_A_OFFSET, MATRIX_B_OFFSET, MATRIX_C_OFFSET, MATRIX_D_OFFSET).map { signedCell(tkhdPayload, (base + it).toLong()) }
    return ROTATION_MATRICES.firstOrNull { it.cells == cells }?.degrees ?: VideoRotation.DEGREES_0
}

private fun handlerType(mdiaPayload: ByteArray): String? {
    for (child in childBoxes(mdiaPayload)) {
        if (child.type == BOX_HDLR) return readAscii(child.payload, HDLR_HANDLER_OFFSET.toLong(), HDLR_HANDLER_BYTES.toLong())
    }
    return null
}

private fun videoTrackRotation(trakPayload: ByteArray): VideoRotation? {
    var rotation: VideoRotation? = null
    var handler: String? = null
    for (child in childBoxes(trakPayload)) {
        if (child.type == BOX_TKHD) rotation = trackRotation(child.payload)
        if (child.type == BOX_MDIA) handler = handlerType(child.payload)
    }
    return if (handler == HANDLER_VIDEO) rotation ?: VideoRotation.DEGREES_0 else null
}

private fun readMoov(body: ByteArray, found: MutableVideoMetadata) {
    var rotationFound = false
    for (child in childBoxes(body)) {
        when {
            child.type == BOX_MVHD -> found.creationUtc = creationTime(child.payload)
            child.type == BOX_TRAK && !rotationFound -> {
                val rotation = videoTrackRotation(child.payload)
                if (rotation != null) {
                    found.rotationDegrees = rotation
                    rotationFound = true
                }
            }
            child.type == BOX_UDTA -> {
                for (entry in childBoxes(child.payload)) {
                    if (entry.type == BOX_XYZ && found.location == null) found.location = xyzLocation(entry.payload)
                }
            }
            child.type == BOX_META -> if (found.location == null) found.location = appleLocation(child.payload)
        }
    }
}

private fun walkTopLevel(source: ByteSource, found: MutableVideoMetadata) {
    var offset = 0L
    var count = 0
    while (count < BOX_WALK_MAX_BOXES && offset < source.size) {
        val peek = source.read(offset, BOX_PEEK_BYTES.toLong())
        val header = parseBoxHeader(peek, source.size - offset)
        if (header.type == BOX_MOOV) {
            if (header.size > MOOV_MAX_BYTES) throw MediaRangeError(MediaText.MALFORMED_STRUCTURE)
            val bodyLength = header.size - header.headerSize
            val body = source.read(offset + header.headerSize, bodyLength)
            requireRange(body, 0, bodyLength)
            readMoov(body, found)
            return
        }
        offset += header.size
        count += 1
    }
}

fun readVideoMetadata(source: ByteSource): VideoMetadata {
    val found = MutableVideoMetadata()
    try {
        walkTopLevel(source, found)
    } catch (error: Exception) {
        found.malformed = true
    }
    return found.freeze()
}
