package dev.kodakiko.data.media

import java.io.File

sealed interface ThumbnailImage {
    data class PlainFile(val file: File) : ThumbnailImage

    class Bytes(val bytes: ByteArray) : ThumbnailImage
}

data class ThumbnailModeSwitch(val running: Boolean, val done: Int, val total: Int) {
    companion object {
        val IDLE = ThumbnailModeSwitch(running = false, done = 0, total = 0)
    }
}
