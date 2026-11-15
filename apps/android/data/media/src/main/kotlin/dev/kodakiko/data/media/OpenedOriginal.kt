package dev.kodakiko.data.media

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class OpenedOriginal(val reader: StreamFileReader, val mimeType: String?, private val onClose: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            reader.close()
        } finally {
            onClose()
        }
    }
}
