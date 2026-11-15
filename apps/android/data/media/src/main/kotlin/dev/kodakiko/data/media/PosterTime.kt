package dev.kodakiko.data.media

fun posterTimeMs(durationMs: Long?): Long =
    if (durationMs == null) MediaConstants.PHOTO_POSTER_TIME_MS else minOf(MediaConstants.POSTER_MAX_TIME_MS, durationMs / MediaConstants.POSTER_DURATION_DIVISOR)
