package dev.kodakiko.data.media

import java.util.Objects

class ThumbnailResult(val bytes: ByteArray, val width: Int, val height: Int) {
    override fun equals(other: Any?): Boolean =
        other is ThumbnailResult && other.width == width && other.height == height && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = Objects.hash(bytes.contentHashCode(), width, height)
}
