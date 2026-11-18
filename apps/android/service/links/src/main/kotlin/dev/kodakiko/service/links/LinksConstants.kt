package dev.kodakiko.service.links

object LinksConstants {
    const val MAX_ENTRIES = 100
    const val LINK_SECRET_BYTES = 32
    const val LINK_PATH_PREFIX = "/s/"
    const val PHOTO_MAX_EDGE = 1600
    const val PHOTO_QUALITY = 85
    const val PHOTO_MIME = "image/jpeg"
    const val WEBM_MIME = "video/webm"
    const val FRAGMENT_MARK = "#"
    const val KEY_BINDING_SEPARATOR = "/"
    const val NO_SKIPS = 0
    const val NO_BYTES = 0L
    const val FIRST_STEP = 0
    const val REGISTERING_TOTAL = 1
    const val EMPTY_NOTE = ""
    val TRAILING_SLASHES = Regex("/+\\z")
}

internal object LinksText {
    const val PHOTO_UNDECODABLE = "the photo cannot be decoded"
    const val PHOTO_ENCODE_FAILED = "the photo cannot be encoded as JPEG"
    const val STRIP_UNDECODABLE = "the photo cannot be re-encoded"
    const val STRIP_FAILED = "the stripped copy still carries metadata"
}
