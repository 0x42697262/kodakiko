package dev.kodakiko.exporting

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private class JavaRawDeflater(level: Int) : RawDeflater {
    private val deflater = Deflater(level, true)
    private val buffer = ByteArray(ZipConstants.INFLATE_BUFFER_BYTES)

    override fun deflate(input: ByteArray, finish: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        deflater.setInput(input)
        if (finish) deflater.finish()
        while (true) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
            val drained = if (finish) deflater.finished() else deflater.needsInput() && count < buffer.size
            if (drained) break
        }
        return out.toByteArray()
    }

    override fun close() {
        deflater.end()
    }
}

internal actual fun rawDeflater(level: Int): RawDeflater = JavaRawDeflater(level)

internal actual fun inflateRaw(input: ByteArray, size: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(input)
        val out = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val count = inflater.inflate(out, filled, size - filled)
            if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) zipMalformed()
            filled += count
        }
        if (!inflater.finished() && inflater.inflate(ByteArray(1)) != 0) zipMalformed()
        return out
    } catch (error: DataFormatException) {
        throw ZipFormatException(ExportText.ZIP_MALFORMED, error)
    } finally {
        inflater.end()
    }
}
