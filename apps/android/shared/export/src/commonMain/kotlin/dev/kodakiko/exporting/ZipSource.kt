package dev.kodakiko.exporting

import dev.kodakiko.crypto.wipe
import dev.kodakiko.exporting.ZipConstants.LOCAL_HEADER_BYTES

class ZipSourceEntry internal constructor(
    val name: String,
    val size: Long,
    internal val method: Int,
    internal val compressedSize: Long,
    internal val localOffset: Long,
)

fun listZip(source: RandomSource): List<ZipSourceEntry> {
    val tailBytes = minOf(source.size, ZipConstants.END_SEARCH_LIMIT.toLong()).toInt()
    val tailStart = source.size - tailBytes
    val tail = source.read(tailStart, tailBytes)
    val end = endRecordOffset(tail)
    val count = readU16(tail, end + ZipConstants.READ_END_ENTRIES)
    if (count == 0) return emptyList()
    val locator = end - ZipConstants.ZIP64_LOCATOR_DISTANCE
    if (locator >= 0 && readU32(tail, locator) == ZipConstants.ZIP64_LOCATOR_SIGNATURE) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    val directoryStart = readU32(tail, end + ZipConstants.READ_END_OFFSET)
    val directoryEnd = tailStart + end
    if (directoryStart > directoryEnd || directoryEnd - directoryStart > Int.MAX_VALUE) zipMalformed()
    val directory = source.read(directoryStart, (directoryEnd - directoryStart).toInt())
    var offset = 0
    val entries = ArrayList<ZipSourceEntry>(count)
    repeat(count) {
        if (offset > directory.size) zipMalformed()
        val record = centralRecord(directory, offset)
        entries.add(ZipSourceEntry(record.name, record.size, record.method, record.compressedSize, record.localOffset))
        offset = record.next
    }
    return entries
}

private fun dataStart(source: RandomSource, entry: ZipSourceEntry): Long {
    if (entry.localOffset + LOCAL_HEADER_BYTES > source.size) zipMalformed()
    val header = source.read(entry.localOffset, LOCAL_HEADER_BYTES)
    if (readU32(header, 0) != ZipConstants.LOCAL_HEADER_SIGNATURE) zipMalformed()
    val start = entry.localOffset + LOCAL_HEADER_BYTES + readU16(header, ZipConstants.READ_LOCAL_NAME_LENGTH) +
        readU16(header, ZipConstants.READ_LOCAL_EXTRA_LENGTH)
    if (start + entry.compressedSize > source.size) zipMalformed()
    return start
}

fun copyZipEntry(source: RandomSource, entry: ZipSourceEntry, sink: (ByteArray) -> Unit) {
    val start = dataStart(source, entry)
    when (entry.method) {
        ZipConstants.METHOD_STORED -> {
            var done = 0L
            while (done < entry.compressedSize) {
                val count = minOf(ZipConstants.COPY_SLICE_BYTES.toLong(), entry.compressedSize - done).toInt()
                sink(source.read(start + done, count))
                done += count
            }
        }
        ZipConstants.METHOD_DEFLATE -> {
            if (entry.size > Int.MAX_VALUE || entry.compressedSize > Int.MAX_VALUE) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
            sink(inflateRaw(source.read(start, entry.compressedSize.toInt()), entry.size.toInt()))
        }
        else -> throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    }
}

fun readZipEntry(source: RandomSource, entry: ZipSourceEntry): ByteArray {
    if (entry.size > Int.MAX_VALUE) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    val out = ByteArray(entry.size.toInt())
    var filled = 0
    copyZipEntry(source, entry) { slice ->
        if (filled + slice.size > out.size) zipMalformed()
        slice.copyInto(out, filled)
        filled += slice.size
        wipe(slice)
    }
    if (filled != out.size) zipMalformed()
    return out
}
