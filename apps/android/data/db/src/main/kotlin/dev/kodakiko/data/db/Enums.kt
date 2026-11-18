package dev.kodakiko.data.db

interface WireValue {
    val wire: String
}

enum class CollectionKind(override val wire: String) : WireValue {
    HOME("home"),
    SHARED("shared"),
    LINK("link"),
}

enum class CollectionRole(override val wire: String) : WireValue {
    OWNER("owner"),
    VIEWER("viewer"),
}

enum class EntryPurpose(override val wire: String) : WireValue {
    JOURNAL("journal"),
    LINK_COPY("link_copy"),
}

enum class EntryLocalState(override val wire: String) : WireValue {
    SYNCED("synced"),
    PENDING_CREATE("pending_create"),
    PENDING_UPDATE("pending_update"),
}

enum class ObjectVariant(override val wire: String) : WireValue {
    ORIGINAL("original"),
    THUMBNAIL("thumbnail"),
}

enum class ObjectLocalState(override val wire: String) : WireValue {
    MISSING("missing"),
    PRESENT("present"),
    UNAVAILABLE("unavailable"),
}

enum class ObjectUploadState(override val wire: String) : WireValue {
    LOCAL("local"),
    QUEUED("queued"),
    UPLOADING("uploading"),
    UPLOADED("uploaded"),
    DEAD("dead"),
}

enum class OutboxState(override val wire: String) : WireValue {
    QUEUED("queued"),
    DEAD("dead"),
}

internal inline fun <reified E> wireValueOf(wire: String): E where E : Enum<E>, E : WireValue =
    enumValues<E>().firstOrNull { it.wire == wire } ?: throw UnknownWireValueError(E::class.java.simpleName, wire)
