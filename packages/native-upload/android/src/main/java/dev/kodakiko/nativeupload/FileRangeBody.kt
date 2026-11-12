package dev.kodakiko.nativeupload

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

class MissingBytesException : IOException()

class FileRangeBody(
  private val file: File,
  private val offset: Long,
  private val length: Long,
  private val onBytes: (Long) -> Unit,
) : RequestBody() {
  @Volatile
  var sentBytes: Long = 0
    private set

  override fun contentType() = UploadConstants.CONTENT_TYPE.toMediaType()

  override fun contentLength(): Long = length

  override fun writeTo(sink: BufferedSink) {
    RandomAccessFile(file, "r").use { source ->
      source.seek(offset)
      val buffer = ByteArray(UploadConstants.COPY_BUFFER_BYTES)
      var remaining = length
      while (remaining > 0) {
        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
        val read = source.read(buffer, 0, wanted)
        if (read < 0) throw MissingBytesException()
        sink.write(buffer, 0, read)
        remaining -= read
        sentBytes += read
        onBytes(read.toLong())
      }
    }
  }
}
