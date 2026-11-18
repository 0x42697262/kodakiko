package dev.kodakiko.domain

object DomainText {
    const val MEDIA_ORDER_NOT_PERMUTATION = "The media order must list every media item exactly once."
    const val REMOVE_LAST_MEDIA = "An entry must keep at least one photo or video."
    const val INVALID_TAG = "A tag is empty or too long after normalizing."
    const val UNKNOWN_MEDIA = "That media item is not part of this entry."
}
