package dev.kodakiko.data.media

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

interface CiphertextSource : Closeable {
    val size: Long

    fun read(offset: Long, length: Int): ByteArray

    companion object {
        fun of(file: File): CiphertextSource = FileCiphertextSource(FileChannel.open(file.toPath(), StandardOpenOption.READ))
    }
}

private class FileCiphertextSource(private val channel: FileChannel) : CiphertextSource {
    override val size: Long get() = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        check(channel.isOpen) { MediaText.SOURCE_CLOSED }
        val available = (channel.size() - offset).coerceIn(0L, length.toLong()).toInt()
        val buffer = ByteBuffer.allocate(available)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) break
        }
        return if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
    }

    override fun close() {
        channel.close()
    }
}
