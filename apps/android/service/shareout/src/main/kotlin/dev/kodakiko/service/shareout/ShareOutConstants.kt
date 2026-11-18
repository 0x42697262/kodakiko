package dev.kodakiko.service.shareout

object ShareOutConstants {
    const val RELEASE_DELAY_MS = 60_000L
    const val NOTE_SEPARATOR = "\n\n"
    const val GENERATED_NAME_PREFIX = "kodakiko-"
    const val EXTENSION_SEPARATOR = "."
    const val FALLBACK_EXTENSION = "bin"
    const val FILE_ID_BYTES = 16
    const val FIRST_NUMBER = 1

    val NAME_EXTENSIONS: Map<String, String> = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "audio/mp4" to "m4a",
        "audio/webm" to "weba",
    )

    val FILE_EXTENSIONS: Map<String, String> = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
    )
}
