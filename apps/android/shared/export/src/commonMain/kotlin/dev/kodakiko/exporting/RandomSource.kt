package dev.kodakiko.exporting

interface RandomSource : AutoCloseable {
    val size: Long

    fun read(position: Long, length: Int): ByteArray

    override fun close() = Unit
}

class ByteArraySource(private val bytes: ByteArray) : RandomSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > bytes.size) throw ZipFormatException(ExportText.ZIP_MALFORMED)
        return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }
}

fun RandomSource.readAll(): ByteArray {
    if (size > Int.MAX_VALUE) throw ZipFormatException(ExportText.ZIP_UNSUPPORTED)
    return read(0, size.toInt())
}

fun RandomSource.startsWithKdkx(): Boolean = isKdkx(read(0, minOf(size, KdkxConstants.MAGIC_TEXT.length.toLong()).toInt()))
