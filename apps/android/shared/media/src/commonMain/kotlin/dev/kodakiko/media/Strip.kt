package dev.kodakiko.media

import dev.kodakiko.media.StripConstants.BITS_PER_BYTE
import dev.kodakiko.media.StripConstants.BYTE_MASK
import dev.kodakiko.media.StripConstants.EBML_DATE_BYTES
import dev.kodakiko.media.StripConstants.UINT32_BITS
import dev.kodakiko.media.StripConstants.UINT32_BYTES
import dev.kodakiko.media.StripConstants.UINT64_BYTES

sealed interface StripSegment {
    class Bytes(val bytes: ByteArray) : StripSegment

    data class Copy(val offset: Long, val length: Long) : StripSegment
}

class StripPlan(val segments: List<StripSegment>, val outputSize: Long, val moov: ByteArray)

private class TopBox(val type: String, val offset: Long, val size: Long)

private class Child(val type: String, val header: Int, val bytes: ByteArray)

private fun topLevelBoxes(source: ByteSource): List<TopBox> {
    val boxes = mutableListOf<TopBox>()
    var offset = 0L
    while (offset < source.size) {
        if (boxes.size > BOX_WALK_MAX_BOXES) throw UnsupportedVideoError(MediaText.TOO_MANY_BOXES)
        val peek = source.read(offset, BOX_LARGE_HEADER_BYTES.toLong())
        if (peek.size < BOX_HEADER_BYTES) throw UnsupportedVideoError(MediaText.TRUNCATED_BOX)
        val declared = readUint32(peek, 0)
        val type = readAscii(peek, UINT32_BYTES.toLong(), UINT32_BYTES.toLong())
        var size = declared
        if (declared == BOX_LARGE_SIZE_MARKER) size = readUint64(peek, BOX_HEADER_BYTES.toLong())
        if (declared == BOX_TO_END_MARKER) size = source.size - offset
        if (size < BOX_HEADER_BYTES || offset + size > source.size) throw UnsupportedVideoError(MediaText.BAD_BOX_SIZE)
        boxes.add(TopBox(type, offset, size))
        offset += size
    }
    return boxes
}

private fun children(bytes: ByteArray): List<Child> {
    val found = mutableListOf<Child>()
    var position = 0L
    while (position < bytes.size) {
        if (bytes.size - position < BOX_HEADER_BYTES) throw UnsupportedVideoError(MediaText.TRUNCATED_CHILD)
        val declared = readUint32(bytes, position)
        val type = readAscii(bytes, position + UINT32_BYTES, UINT32_BYTES.toLong())
        var header = BOX_HEADER_BYTES
        var size = declared
        if (declared == BOX_LARGE_SIZE_MARKER) {
            header = BOX_LARGE_HEADER_BYTES
            size = readUint64(bytes, position + BOX_HEADER_BYTES)
        }
        if (declared == BOX_TO_END_MARKER) size = bytes.size - position
        if (size < header || position + size > bytes.size) throw UnsupportedVideoError(MediaText.BAD_CHILD_SIZE)
        found.add(Child(type, header, subarray(bytes, position, position + size)))
        position += size
    }
    return found
}

private fun boxBytes(type: String, payload: ByteArray): ByteArray {
    val out = ByteArray(BOX_HEADER_BYTES + payload.size)
    writeUint32(out, 0, out.size.toLong())
    latin1Bytes(type).copyInto(out, UINT32_BYTES, 0, UINT32_BYTES)
    payload.copyInto(out, BOX_HEADER_BYTES)
    return out
}

private fun zeroTimes(payload: ByteArray): ByteArray {
    val copy = payload.copyOf()
    val width = if (copy.firstOrNull()?.toInt() == StripConstants.VERSION_1) UINT64_BYTES else UINT32_BYTES
    val start = minOf(StripConstants.TIME_FIELDS_OFFSET, copy.size)
    val end = minOf(StripConstants.TIME_FIELDS_OFFSET + StripConstants.TIME_FIELD_COUNT * width, copy.size)
    copy.fill(0, start, end)
    return copy
}

private fun readEntry(bytes: ByteArray, at: Long, wide: Boolean): Long {
    if (!wide) return readUint32(bytes, at)
    var value = 0UL
    for (index in 0 until UINT64_BYTES) value = (value shl BITS_PER_BYTE) or byteAt(bytes, at + index).toULong()
    return if (value > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else value.toLong()
}

private fun shiftOffsets(payload: ByteArray, wide: Boolean, mapOffset: (Long) -> Long): ByteArray {
    val copy = payload.copyOf()
    if (copy.size < StripConstants.ENTRY_COUNT_OFFSET + UINT32_BYTES) throw RangeError(MediaText.DATA_VIEW_OUT_OF_BOUNDS)
    val count = readUint32(copy, StripConstants.ENTRY_COUNT_OFFSET.toLong())
    val width = if (wide) UINT64_BYTES else UINT32_BYTES
    if (StripConstants.ENTRIES_OFFSET + count * width > copy.size) throw UnsupportedVideoError(MediaText.BAD_CHUNK_TABLE)
    for (index in 0 until count) {
        val at = StripConstants.ENTRIES_OFFSET + index * width
        val mapped = mapOffset(readEntry(copy, at, wide))
        if (wide) {
            writeUint64(copy, at.toInt(), mapped)
        } else {
            if (mapped > StripConstants.UINT32_MAX || mapped < 0) throw UnsupportedVideoError(MediaText.CHUNK_OFFSET_OVERFLOW)
            writeUint32(copy, at.toInt(), mapped)
        }
    }
    return copy
}

private fun rewrite(box: Child, mapOffset: (Long) -> Long): ByteArray? {
    if (box.type in StripConstants.REMOVED) return null
    val payload = subarray(box.bytes, box.header.toLong())
    return when {
        box.type in StripConstants.CONTAINERS -> boxBytes(box.type, concatBytes(children(payload).mapNotNull { rewrite(it, mapOffset) }))
        box.type in StripConstants.TIMED -> boxBytes(box.type, zeroTimes(payload))
        box.type == StripConstants.BOX_STCO -> boxBytes(box.type, shiftOffsets(payload, false, mapOffset))
        box.type == StripConstants.BOX_CO64 -> boxBytes(box.type, shiftOffsets(payload, true, mapOffset))
        else -> boxBytes(box.type, payload)
    }
}

fun moovHasMetadata(moov: ByteArray): Boolean {
    fun visit(bytes: ByteArray): Boolean =
        children(bytes).any { child ->
            child.type in StripConstants.METADATA_TYPES ||
                (child.type in StripConstants.CONTAINERS && visit(subarray(child.bytes, child.header.toLong())))
        }
    return visit(subarray(moov, BOX_HEADER_BYTES.toLong()))
}

fun planIsoBmffStrip(source: ByteSource): StripPlan {
    val boxes = topLevelBoxes(source)
    if (boxes.any { it.type == StripConstants.BOX_MOOF }) throw UnsupportedVideoError(MediaText.FRAGMENTED_MP4)
    val kept = boxes.filter { it.type in StripConstants.KEPT_TOP_LEVEL }
    val moovBoxes = kept.filter { it.type == BOX_MOOV }
    val moovBox = moovBoxes.firstOrNull()
    if (moovBox == null || moovBoxes.size != 1) throw UnsupportedVideoError(MediaText.MISSING_MOOV)
    if (moovBox.size > MOOV_MAX_BYTES) throw UnsupportedVideoError(MediaText.MOOV_TOO_LARGE)
    val originalMoov = source.read(moovBox.offset, moovBox.size)
    val moovChild = children(originalMoov).firstOrNull() ?: throw UnsupportedVideoError(MediaText.MISSING_MOOV)
    val sized = rewrite(moovChild) { it } ?: throw UnsupportedVideoError(MediaText.MISSING_MOOV)
    val newStarts = mutableMapOf<TopBox, Long>()
    var position = 0L
    for (box in kept) {
        newStarts[box] = position
        position += if (box === moovBox) sized.size.toLong() else box.size
    }
    val mdats = kept.filter { it.type == StripConstants.BOX_MDAT }
    val mapOffset = { offset: Long ->
        val home = mdats.firstOrNull { offset >= it.offset && offset < it.offset + it.size }
            ?: throw UnsupportedVideoError(MediaText.CHUNK_OFFSET_OUTSIDE_MDAT)
        offset + (newStarts[home] ?: 0L) - home.offset
    }
    val moov = rewrite(moovChild, mapOffset) ?: throw UnsupportedVideoError(MediaText.MISSING_MOOV)
    val segments = kept.map { box ->
        when {
            box === moovBox -> StripSegment.Bytes(moov)
            box.type == StripConstants.BOX_MDAT -> StripSegment.Copy(box.offset, box.size)
            else -> StripSegment.Bytes(source.read(box.offset, box.size))
        }
    }
    return StripPlan(segments, position, moov)
}

fun streamStripped(source: ByteSource, segments: List<StripSegment>, chunkBytes: Int): Sequence<ByteArray> = sequence {
    for (segment in segments) {
        when (segment) {
            is StripSegment.Bytes -> if (segment.bytes.isNotEmpty()) yield(segment.bytes)
            is StripSegment.Copy -> {
                var done = 0L
                while (done < segment.length) {
                    yield(source.read(segment.offset + done, minOf(chunkBytes.toLong(), segment.length - done)))
                    done += chunkBytes
                }
            }
        }
    }
}

private class EbmlHeader(val id: Long, val headerBytes: Int, val size: Long?)

private fun leadingMarkerLength(first: Int): Int = first.countLeadingZeroBits() - (UINT32_BITS - BITS_PER_BYTE) + 1

private fun readEbmlHeader(bytes: ByteArray): EbmlHeader? {
    val first = bytes.firstOrNull()?.toInt()?.and(BYTE_MASK) ?: return null
    if (first == 0) return null
    val idLength = leadingMarkerLength(first)
    if (idLength > StripConstants.EBML_MAX_ID_BYTES || bytes.size < idLength + 1) return null
    var id = 0L
    for (index in 0 until idLength) id = id * (BYTE_MASK + 1) + (bytes[index].toInt() and BYTE_MASK)
    val sizeFirst = bytes[idLength].toInt() and BYTE_MASK
    if (sizeFirst == 0) return null
    val sizeLength = leadingMarkerLength(sizeFirst)
    if (sizeLength > StripConstants.EBML_MAX_SIZE_BYTES || bytes.size < idLength + sizeLength) return null
    var size = (sizeFirst and (BYTE_MASK shr sizeLength)).toLong()
    var unknown = size == (BYTE_MASK shr sizeLength).toLong()
    for (index in 1 until sizeLength) {
        val value = bytes[idLength + index].toInt() and BYTE_MASK
        unknown = unknown && value == BYTE_MASK
        size = size * (BYTE_MASK + 1) + value
    }
    return EbmlHeader(id, idLength + sizeLength, if (unknown) null else size)
}

private fun findDateUtc(source: ByteSource): Long? {
    var offset = 0L
    var end = source.size
    var insideSegment = false
    var insideInfo = false
    while (offset < end) {
        val header = readEbmlHeader(source.read(offset, StripConstants.EBML_HEADER_PEEK_BYTES.toLong())) ?: return null
        val dataStart = offset + header.headerBytes
        val size = header.size
        if (!insideSegment && header.id == StripConstants.EBML_ID_SEGMENT) {
            insideSegment = true
            offset = dataStart
            if (size != null) end = minOf(end, dataStart + size)
            continue
        }
        if (insideSegment && !insideInfo && header.id == StripConstants.EBML_ID_INFO && size != null) {
            insideInfo = true
            offset = dataStart
            end = dataStart + size
            continue
        }
        if (insideInfo && header.id == StripConstants.EBML_ID_DATE_UTC) return if (size == EBML_DATE_BYTES.toLong()) dataStart else null
        if (header.id == StripConstants.EBML_ID_CLUSTER || size == null) return null
        offset = dataStart + size
    }
    return null
}

fun planWebmDateStrip(source: ByteSource): StripPlan {
    val at = findDateUtc(source)
        ?: return StripPlan(listOf(StripSegment.Copy(0, source.size)), source.size, ByteArray(0))
    return StripPlan(
        segments = listOf(
            StripSegment.Copy(0, at),
            StripSegment.Bytes(ByteArray(EBML_DATE_BYTES)),
            StripSegment.Copy(at + EBML_DATE_BYTES, source.size - at - EBML_DATE_BYTES),
        ),
        outputSize = source.size,
        moov = ByteArray(0),
    )
}
