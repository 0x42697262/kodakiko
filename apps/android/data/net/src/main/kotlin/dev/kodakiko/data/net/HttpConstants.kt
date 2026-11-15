package dev.kodakiko.data.net

object HttpConstants {
    const val TIMEOUT_MS = 20_000L
    const val RETRY_ATTEMPTS = 3
    const val RETRY_BASE_MS = 500.0
    const val RETRY_CAP_MS = 8000.0
    const val RETRY_AFTER_MAX_MS = 60_000L
    const val BACKOFF_FACTOR = 2.0
    const val MS_PER_SECOND = 1000.0
    const val NO_TIMEOUT = 0L
    const val STATUS_OK = 200
    const val STATUS_MULTIPLE_CHOICES = 300
    const val STATUS_UNAUTHORIZED = 401
    const val STATUS_TOO_MANY_REQUESTS = 429
    const val STATUS_SERVICE_UNAVAILABLE = 503
    const val STATUS_SERVER_ERROR_MIN = 500
}

object CallConstants {
    const val RESTORE_TIMEOUT_MS = 5_000L
    const val SIGN_OUT_TIMEOUT_MS = 5_000L
}
