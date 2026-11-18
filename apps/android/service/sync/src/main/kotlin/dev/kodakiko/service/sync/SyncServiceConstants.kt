package dev.kodakiko.service.sync

object SyncServiceConstants {
    const val CREATED_REVISION = 1L
    const val NO_REVISION = 0L
    const val UPDATE_RERUN_LIMIT = 20
    const val CONFLICT_INLINE_RETRIES = 1
    const val CONFLICT_RESOLVE_ATTEMPTS = 5
    const val MAX_RESYNC_RESTARTS = 3
    const val INITIAL_CURSOR = 0L
    const val SINGLE_PART_NUMBER = 0
    const val FIRST_PART_NUMBER = 1L
    const val NO_PLAINTEXT_SIZE = 0L
    const val NEVER_ACCESSED = 0L
    const val NOT_KICKED = 0L
    const val EMPTY_DURATION_MS = 0L
    const val STATUS_NOT_FOUND = 404
    const val STATUS_UNAUTHORIZED = 401
    const val STATUS_CONFLICT = 409
    const val STATUS_TOO_MANY_REQUESTS = 429
    const val STATUS_SERVER_ERROR_MIN = 500
}

object SyncLogEvents {
    const val ROLLBACK_REJECTED = "sync.rollback_rejected"
    const val UPLOAD_CONFIRM_FAILED = "sync.upload_confirm_failed"
    const val PASS_FAILED = "sync.pass_failed"
    const val EVENT_HANDLER_FAILED = "sync.event_handler_failed"
}

object SyncLogFields {
    const val ENTRY_ID = "entryId"
    const val PULLED_REVISION = "pulledRevision"
    const val BASE_REVISION = "baseRevision"
    const val OBJECT_ID = "objectId"
    const val ERROR = "error"
}

object SyncErrorCodes {
    const val NETWORK = "NETWORK"
    const val UNKNOWN = "UNKNOWN"
    const val MISSING_FILE = "MISSING_FILE"
}

object SyncPayloadKeys {
    const val ENTRY_ID = "entryId"
    const val MEDIA_ID = "mediaId"
}

object SyncText {
    const val RESYNC_LOOP = "The server keeps asking for a full resync."
    const val UNKNOWN_OUTBOX_KIND = "Unknown outbox item kind."
    const val INVALID_STORED_VALUE = "A stored sync value has an unexpected type."
}
