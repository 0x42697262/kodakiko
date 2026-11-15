package dev.kodakiko.media

interface ByteSource {
    val size: Long

    fun read(offset: Long, length: Long): ByteArray
}

fun byteSourceFromBytes(bytes: ByteArray): ByteSource = object : ByteSource {
    override val size: Long = bytes.size.toLong()

    override fun read(offset: Long, length: Long): ByteArray {
        val start = maxOf(0L, offset)
        return subarray(bytes, start, start + maxOf(0L, length))
    }
}
