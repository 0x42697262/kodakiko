package dev.kodakiko.data.media

data class CacheUsage(
    val originalsBytes: Long,
    val thumbnailsBytes: Long,
    val pinnedBytes: Long,
    val pendingUploadBytes: Long,
)
