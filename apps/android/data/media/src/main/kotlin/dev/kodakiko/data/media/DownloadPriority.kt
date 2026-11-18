package dev.kodakiko.data.media

enum class DownloadPriority(val wire: String) {
    OPENED("opened"),
    VISIBLE("visible"),
    BACKGROUND("background"),
}
