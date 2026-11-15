package dev.kodakiko.service.links

import dev.kodakiko.data.upload.FailureReason

enum class LinkInputReason(val wire: String) {
    LOCKED("locked"),
    ENTRIES("entries"),
    MEDIA("media"),
    TITLE("title"),
    ENTRY("entry"),
    OBJECTS("objects"),
    NOTHING("nothing"),
}

class LinkInputException(val reason: LinkInputReason) : Exception(reason.wire)

class StripUndecodableException : Exception(LinksText.STRIP_UNDECODABLE)

class StripFailedException : Exception(LinksText.STRIP_FAILED)

class LinkUploadFailedException(val reason: FailureReason) : Exception(reason.wire)
