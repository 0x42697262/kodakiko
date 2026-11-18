package dev.kodakiko.data.media

class OriginalHandle internal constructor(val objectId: String, private val onRelease: () -> Unit) {
    fun release() = onRelease()
}
