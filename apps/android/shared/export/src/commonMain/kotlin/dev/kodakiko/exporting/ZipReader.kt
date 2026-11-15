package dev.kodakiko.exporting

import dev.kodakiko.exporting.ZipConstants.CENTRAL_HEADER_BYTES
import dev.kodakiko.exporting.ZipConstants.END_BYTES
import dev.kodakiko.exporting.ZipConstants.LOCAL_HEADER_BYTES
import dev.kodakiko.exporting.ZipConstants.U32_MAX

class ZipFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ZipEntry(val name: String, val content: ByteArray)

private class CentralRecord(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long, val next: Int)

private fun endRecordOffset(zip: ByteArray): Int {
    var offset = zip.size - END_BYTES
    while (offset >= 0 && readU32(zip, offset) != ZipConstants.END_SIGNATURE) {
        if (offset == 0 || zip.size - offset > ZipConstants.END_SEARCH_LIMIT) zipMalformed()
        offset -= 1
    }
    if (offset < 0) zipMalformed()
    return offset
}

private fun centralRecord(zip: ByteArray, offset: Int): CentralRecord {
    if (readU32(zip, offset) != ZipConstants.CENTRAL_HEADER_SIGNATURE) zipMalformed()
    val nameLength = readU16(zip, offset + ZipConstants.READ_NAME_LENGTH)
    val extraLength = readU16(zip, offset + ZipConstants.READ_EXTRA_LENGTH)
    val commentLength = readU16(zip, offset + ZipConstants.READ_COMMENT_LENGTH)
    val nameStart = offset + CENTRAL_HEADER_BYTES
    if (nameStart + nameLength > zip.size) zipMalformed()
    val nameBytes = zip.copyOfRange(nameStart, nameStart + nameLength)
    val utf8 = readU16(zip, offset + ZipConstants.READ_FLAGS) and ZipConstants.FLAG_UTF8 != 0
    val record = CentralRecord(
        name = if (utf8) nameBytes.decodeToString() else latin1String(nameBytes),
        method = readU16(zip, offset + ZipConstants.READ_METHOD),
        compressedSize = readU32(zip, offset + ZipConstants.READ_COMPRESSED_SIZE),
        size = readU32(zip, offset + ZipConstants.READ_SIZE),
        localOffset = readU32(zip, offset + ZipConstants.READ_LOCAL_OFFSET),
        next = nameStart + nameLength + extraLength + commentLength,
    )
    if (record.compressedSize == U32_MAX || record.size == U32_MAX || record.localOffset == U32_MAX) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    return record
}

private fun content(zip: ByteArray, record: CentralRecord): ByteArray {
    val local = record.localOffset.toInt()
    if (record.localOffset > zip.size || readU32(zip, local) != ZipConstants.LOCAL_HEADER_SIGNATURE) zipMalformed()
    val start = local.toLong() + LOCAL_HEADER_BYTES + readU16(zip, local + ZipConstants.READ_LOCAL_NAME_LENGTH) +
        readU16(zip, local + ZipConstants.READ_LOCAL_EXTRA_LENGTH)
    val end = start + record.compressedSize
    if (end > zip.size) zipMalformed()
    val data = zip.copyOfRange(start.toInt(), end.toInt())
    return when (record.method) {
        ZipConstants.METHOD_STORED -> data
        ZipConstants.METHOD_DEFLATE -> {
            if (record.size > Int.MAX_VALUE) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
            inflateRaw(data, record.size.toInt())
        }
        else -> throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    }
}

fun readZip(zip: ByteArray): List<ZipEntry> {
    val end = endRecordOffset(zip)
    val count = readU16(zip, end + ZipConstants.READ_END_ENTRIES)
    if (count == 0) return emptyList()
    val locator = end - ZipConstants.ZIP64_LOCATOR_DISTANCE
    if (locator >= 0 && readU32(zip, locator) == ZipConstants.ZIP64_LOCATOR_SIGNATURE) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    var offset = readU32(zip, end + ZipConstants.READ_END_OFFSET)
    val entries = ArrayList<ZipEntry>(count)
    repeat(count) {
        if (offset > zip.size) zipMalformed()
        val record = centralRecord(zip, offset.toInt())
        entries.add(ZipEntry(record.name, content(zip, record)))
        offset = record.next.toLong()
    }
    return entries
}
