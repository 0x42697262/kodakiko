package dev.kodakiko.service.backup

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.exporting.KDKX_EXTENSION
import dev.kodakiko.exporting.KDKX_SALT_BYTES
import dev.kodakiko.exporting.KdkxParams
import dev.kodakiko.exporting.createKdkxWriter
import dev.kodakiko.exporting.deriveKdkxKey

fun encryptedDestination(sodium: Sodium, inner: ExportDestination, passphrase: String, opsLimit: Long, memLimitBytes: Long): ExportDestination {
    val params = KdkxParams(sodium.randomBytes(KDKX_SALT_BYTES), opsLimit, memLimitBytes)
    val key = deriveKdkxKey(sodium, passphrase, params)
    return object : ExportDestination {
        override val partMaxBytes: Long = inner.partMaxBytes

        override fun createPart(name: String): ExportPart {
            val sink = inner.createPart(name + KDKX_EXTENSION)
            val writer = createKdkxWriter(sodium, key, params)
            sink.write(writer.header)
            return object : ExportPart {
                override fun write(bytes: ByteArray) {
                    writer.push(bytes).forEach(sink::write)
                }

                override fun close() {
                    sink.write(writer.finish())
                    sink.close()
                }

                override fun abort() {
                    sink.abort()
                }
            }
        }
    }
}
