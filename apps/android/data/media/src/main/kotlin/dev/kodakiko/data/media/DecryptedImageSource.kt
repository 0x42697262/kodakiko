package dev.kodakiko.data.media

import okio.Buffer
import okio.Source
import okio.Timeout
import java.io.IOException

internal class DecryptedImageSource(private val original: OpenedOriginal) : Source {
    private var position = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { MediaText.negativeByteCount(byteCount) }
        val remaining = original.reader.size - position
        if (remaining <= 0L) return MediaConstants.END_OF_STREAM.toLong()
        val length = minOf(byteCount, remaining, MediaConstants.IMAGE_READ_BYTES.toLong()).toInt()
        if (length == 0) return 0L
        val plaintext = decrypting { original.reader.read(position, length) }
        try {
            if (plaintext.isEmpty()) return MediaConstants.END_OF_STREAM.toLong()
            sink.write(plaintext)
        } finally {
            plaintext.fill(0)
        }
        position += plaintext.size
        return plaintext.size.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        original.close()
    }

    private inline fun <T> decrypting(work: () -> T): T = try {
        work()
    } catch (failure: IOException) {
        throw failure
    } catch (failure: Exception) {
        throw IOException(failure)
    }
}
