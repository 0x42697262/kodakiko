package dev.kodakiko.exporting

import dev.kodakiko.exporting.ZipConstants.CENTRAL_HEADER_BYTES
import dev.kodakiko.exporting.ZipConstants.DATA_DESCRIPTOR_BYTES
import dev.kodakiko.exporting.ZipConstants.END_BYTES
import dev.kodakiko.exporting.ZipConstants.LOCAL_HEADER_BYTES
import dev.kodakiko.exporting.ZipConstants.U16_MAX
import dev.kodakiko.exporting.ZipConstants.U32_MAX
import dev.kodakiko.exporting.ZipConstants.VERSION
import dev.kodakiko.js.JsDate

fun interface ZipSink {
    fun write(bytes: ByteArray)
}

fun interface ZipTimeZone {
    fun offsetMillisAt(utcMillis: Double): Double

    companion object {
        val UTC: ZipTimeZone = ZipTimeZone { 0.0 }
    }
}

interface ZipFileWriter {
    fun push(bytes: ByteArray, final: Boolean)
}

class ZipWriteException(message: String) : IllegalStateException(message)

private class DosDateTime(val time: Int, val date: Int)

private class WrittenFile(
    val name: ByteArray,
    val flags: Int,
    val method: Int,
    val dos: DosDateTime,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val offset: Long,
)

class ZipWriter(private val sink: ZipSink, private val timeZone: ZipTimeZone) {
    private val written = mutableListOf<WrittenFile>()
    private var bytesWritten = 0L
    private var open: OpenFile? = null
    private var finished = false

    fun addFile(name: String, compress: Boolean, mtime: Double): ZipFileWriter {
        if (finished) throw ZipWriteException(ExportText.ZIP_ARCHIVE_FINISHED)
        if (open != null) throw ZipWriteException(ExportText.ZIP_FILE_STILL_OPEN)
        val encoded = utf8Bytes(name)
        if (encoded.size > U16_MAX) throw ZipWriteException(ExportText.ZIP_NAME_TOO_LONG)
        val flags = ZipConstants.FLAG_DATA_DESCRIPTOR or (if (encoded.size != name.length) ZipConstants.FLAG_UTF8 else 0)
        val method = if (compress) ZipConstants.METHOD_DEFLATE else ZipConstants.METHOD_STORED
        val file = OpenFile(encoded, flags, method, dosDateTime(clampZipDate(mtime)), bytesWritten, if (compress) rawDeflater(ZIP_DEFLATE_LEVEL) else null)
        emit(
            LittleEndianWriter(LOCAL_HEADER_BYTES + encoded.size)
                .u32(ZipConstants.LOCAL_HEADER_SIGNATURE).u16(VERSION).u16(flags).u16(method).u16(file.dos.time).u16(file.dos.date)
                .u32(0).u32(0).u32(0).u16(encoded.size).u16(0).raw(encoded).bytes,
        )
        open = file
        return file
    }

    fun finish() {
        if (finished) throw ZipWriteException(ExportText.ZIP_ARCHIVE_FINISHED)
        if (open != null) throw ZipWriteException(ExportText.ZIP_FILE_STILL_OPEN)
        finished = true
        if (written.size > U16_MAX) throw ZipWriteException(ExportText.ZIP_TOO_LARGE)
        val directorySize = written.sumOf { CENTRAL_HEADER_BYTES + it.name.size }
        val out = LittleEndianWriter(directorySize + END_BYTES)
        for (file in written) {
            out.u32(ZipConstants.CENTRAL_HEADER_SIGNATURE).u16(VERSION).u16(VERSION).u16(file.flags).u16(file.method).u16(file.dos.time).u16(file.dos.date)
                .u32(file.crc).u32(file.compressedSize).u32(file.size).u16(file.name.size).u16(0).u16(0).u16(0).u16(0).u32(0).u32(file.offset).raw(file.name)
        }
        out.u32(ZipConstants.END_SIGNATURE).u16(0).u16(0).u16(written.size).u16(written.size).u32(directorySize.toLong()).u32(checkedU32(bytesWritten)).u16(0)
        emit(out.bytes)
    }

    private fun dosDateTime(time: Double): DosDateTime {
        val local = JsDate.fields(time + timeZone.offsetMillisAt(time)) ?: throw ZipWriteException(ExportText.ZIP_DATE_OUT_OF_RANGE)
        val year = local.year - ZipConstants.DOS_YEAR_BASE
        if (year < 0 || year > ZipConstants.DOS_YEAR_MAX) throw ZipWriteException(ExportText.ZIP_DATE_OUT_OF_RANGE)
        return DosDateTime(
            time = (local.hours.toInt() shl ZipConstants.DOS_HOUR_SHIFT) or (local.minutes.toInt() shl ZipConstants.DOS_MINUTE_SHIFT) or
                (local.seconds.toInt() shr ZipConstants.DOS_SECOND_SHIFT),
            date = (year.toInt() shl ZipConstants.DOS_YEAR_SHIFT) or ((local.monthIndex.toInt() + 1) shl ZipConstants.DOS_MONTH_SHIFT) or local.day.toInt(),
        )
    }

    private fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytesWritten += bytes.size
        sink.write(bytes)
    }

    private fun checkedU32(value: Long): Long = if (value > U32_MAX) throw ZipWriteException(ExportText.ZIP_TOO_LARGE) else value

    private inner class OpenFile(
        val name: ByteArray,
        val flags: Int,
        val method: Int,
        val dos: DosDateTime,
        val offset: Long,
        private val deflater: RawDeflater?,
    ) : ZipFileWriter {
        private val crc = Crc32()
        private var size = 0L
        private var compressedSize = 0L
        private var done = false

        override fun push(bytes: ByteArray, final: Boolean) {
            if (done) throw ZipWriteException(ExportText.ZIP_FILE_FINISHED)
            crc.update(bytes)
            size += bytes.size
            val data = deflater?.deflate(bytes, final) ?: bytes.copyOf()
            compressedSize += data.size
            emit(data)
            if (final) close()
        }

        private fun close() {
            done = true
            deflater?.close()
            val crcValue = crc.value()
            emit(
                LittleEndianWriter(DATA_DESCRIPTOR_BYTES)
                    .u32(ZipConstants.DATA_DESCRIPTOR_SIGNATURE).u32(crcValue).u32(checkedU32(compressedSize)).u32(checkedU32(size)).bytes,
            )
            written.add(WrittenFile(name, flags, method, dos, crcValue, compressedSize, size, checkedU32(offset)))
            open = null
        }
    }
}
