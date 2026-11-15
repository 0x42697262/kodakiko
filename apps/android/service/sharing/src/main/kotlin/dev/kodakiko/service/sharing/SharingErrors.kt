package dev.kodakiko.service.sharing

enum class SharingErrorReason(val wire: String) {
    OFFLINE("offline"),
    DISABLED("disabled"),
    LOCKED("locked"),
    USER_NOT_FOUND("userNotFound"),
    ALREADY_SHARED("alreadyShared"),
    NOT_OWNER("notOwner"),
}

class SharingException(val reason: SharingErrorReason) : Exception(reason.wire)
