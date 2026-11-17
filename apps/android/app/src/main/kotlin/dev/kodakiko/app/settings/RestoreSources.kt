package dev.kodakiko.app.settings

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.kodakiko.data.files.TempFile
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.TempPurpose
import dev.kodakiko.exporting.RandomSource
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private fun FileChannel.readFully(position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    val buffer = ByteBuffer.wrap(out)
    while (buffer.hasRemaining()) {
        val count = read(buffer, position + buffer.position())
        if (count < 0) throw EOFException()
    }
    return out
}

private class DescriptorSource(private val descriptor: ParcelFileDescriptor) : RandomSource {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val channel = input.channel

    override val size: Long = descriptor.statSize

    override fun read(position: Long, length: Int): ByteArray = synchronized(this) { channel.readFully(position, length) }

    override fun close() {
        input.close()
        descriptor.close()
    }
}

private class TempCopySource(private val temp: TempFile) : RandomSource {
    private val file = RandomAccessFile(temp.file, SettingsAdapterConstants.READ_MODE)

    override val size: Long = file.length()

    override fun read(position: Long, length: Int): ByteArray = synchronized(this) { file.channel.readFully(position, length) }

    override fun close() {
        file.close()
        temp.delete()
    }
}

class RestoreSources(private val context: Context, private val tempFiles: TempFiles) {
    fun open(uri: Uri): RandomSource {
        val descriptor = context.contentResolver.openFileDescriptor(uri, SettingsAdapterConstants.READ_MODE) ?: throw FileNotFoundException(uri.toString())
        if (descriptor.statSize >= 0) return DescriptorSource(descriptor)
        descriptor.close()
        return copied(uri)
    }

    private fun copied(uri: Uri): RandomSource {
        val temp = tempFiles.create(TempPurpose.IMPORT, SettingsAdapterConstants.FALLBACK_EXTENSION)
        try {
            temp.use { out ->
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                input.use { stream ->
                    val buffer = ByteArray(SettingsAdapterConstants.COPY_BUFFER_BYTES)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        out.write(buffer.copyOf(count))
                    }
                }
            }
            return TempCopySource(temp)
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
    }
}
