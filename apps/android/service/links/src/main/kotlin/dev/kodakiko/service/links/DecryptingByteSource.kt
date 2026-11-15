package dev.kodakiko.service.links

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.media.StreamFileReader
import dev.kodakiko.media.ByteSource
import java.io.Closeable

class DecryptingByteSource internal constructor(private val reader: StreamFileReader) :
    ByteSource,
    Closeable {
    override val size: Long get() = reader.size

    override fun read(offset: Long, length: Long): ByteArray = reader.read(offset, length.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())

    override fun close() {
        reader.close()
    }
}

fun openDecryptingByteSource(sodium: Sodium, files: LinkFiles, objectId: String, variantKey: ByteArray, plaintextSize: Long): DecryptingByteSource =
    DecryptingByteSource(StreamFileReader.open(sodium, files.openCiphertext(objectId), variantKey, plaintextSize))
