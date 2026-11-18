package dev.kodakiko.exporting

internal interface RawDeflater {
    fun deflate(input: ByteArray, finish: Boolean): ByteArray

    fun close()
}

internal expect fun rawDeflater(level: Int): RawDeflater

internal expect fun inflateRaw(input: ByteArray, size: Int): ByteArray
