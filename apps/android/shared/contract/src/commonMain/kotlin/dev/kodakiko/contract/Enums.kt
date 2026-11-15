package dev.kodakiko.contract

enum class UserStatus(override val wire: String) : WireEnum {
    ACTIVE("active"),
    PENDING_DELETION("pending_deletion"),
}

enum class UserRole(override val wire: String) : WireEnum {
    ADMIN("admin"),
    MEMBER("member"),
}

enum class SignupMode(override val wire: String) : WireEnum {
    INVITE("invite"),
    OPEN("open"),
}

enum class ObjectVariant(override val wire: String) : WireEnum {
    ORIGINAL("original"),
    THUMBNAIL("thumbnail"),
}

enum class ObjectState(override val wire: String) : WireEnum {
    PENDING("pending"),
    UPLOADED("uploaded"),
    DELETING("deleting"),
}

enum class CollectionKind(override val wire: String) : WireEnum {
    HOME("home"),
    SHARED("shared"),
    LINK("link"),
}

enum class CollectionRole(override val wire: String) : WireEnum {
    OWNER("owner"),
    VIEWER("viewer"),
}

enum class EntryPurpose(override val wire: String) : WireEnum {
    JOURNAL("journal"),
    LINK_COPY("link_copy"),
}

enum class LinkExpiry(override val wire: String) : WireEnum {
    ONE_HOUR("1h"),
    ONE_DAY("1d"),
    SEVEN_DAYS("7d"),
    FOURTEEN_DAYS("14d"),
    THIRTY_DAYS("30d"),
    NEVER("never"),
    ONE_VIEW("one_view"),
}

enum class AdminRefusalReason(override val wire: String) : WireEnum {
    SELF_DEMOTION("selfDemotion"),
    LAST_ADMIN("lastAdmin"),
}

enum class MediaKind(override val wire: String) : WireEnum {
    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
}

enum class LocationSource(override val wire: String) : WireEnum {
    EXIF("exif"),
    DEVICE("device"),
}

enum class ErrorCode : WireEnum {
    MALFORMED_REQUEST,
    HTTPS_REQUIRED,
    UNAUTHENTICATED,
    SESSION_EXPIRED,
    INVALID_CREDENTIALS,
    FORBIDDEN,
    FEATURE_DISABLED,
    QUOTA_EXCEEDED,
    INVITE_INVALID,
    LINK_TOKEN_INVALID,
    LINK_EXPIRED,
    LINK_VIEW_EXPIRED,
    ACCOUNT_PENDING_DELETION,
    NOT_FOUND,
    REVISION_CONFLICT,
    ID_CONFLICT,
    USERNAME_TAKEN,
    ALREADY_SHARED,
    PAYLOAD_TOO_LARGE,
    VALIDATION_FAILED,
    KDF_PARAMS_TOO_WEAK,
    UPLOAD_SIZE_MISMATCH,
    UPLOAD_INCOMPLETE,
    RATE_LIMITED,
    INTERNAL,
    STORAGE_UNAVAILABLE,
    ;

    override val wire: String get() = name
}
