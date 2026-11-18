package dev.kodakiko.data.media

import dev.kodakiko.media.ByteSource
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

internal class FileByteSource(file: File) :
    ByteSource,
    Closeable {
    private val handle = RandomAccessFile(file, MediaKeys.READ_ONLY_MODE)

    override val size: Long = handle.length()

    override fun read(offset: Long, length: Long): ByteArray {
        val start = offset.coerceIn(0L, size)
        val count = Math.toIntExact(minOf(maxOf(0L, length), size - start))
        val bytes = ByteArray(count)
        handle.seek(start)
        handle.readFully(bytes)
        return bytes
    }

    override fun close() {
        handle.close()
    }
}
