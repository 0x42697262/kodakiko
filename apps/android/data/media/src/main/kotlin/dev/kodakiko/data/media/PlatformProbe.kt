package dev.kodakiko.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import dev.kodakiko.media.readMovieDurationMs
import java.io.File

class PlatformProbe(context: Context) {
    fun probe(file: File, kind: MediaKind): ProbeResult =
        try {
            when (kind) {
                MediaKind.PHOTO -> probePhoto(file)
                MediaKind.VIDEO -> probeVideo(file)
            }
        } catch (_: Exception) {
            ProbeResult.UNDECODABLE
        }

    private fun probePhoto(file: File): ProbeResult {
        val size = decodePhotoBounds(file) ?: return ProbeResult.UNDECODABLE
        val upright = readPhotoOrientation(file).upright(size)
        return ProbeResult(upright.width, upright.height, null, true, 0)
    }

    private fun probeVideo(file: File): ProbeResult = withRetriever(file) { retriever ->
        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == MediaKeys.RETRIEVER_YES
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val durationMs = FileByteSource(file).use { readMovieDurationMs(it) }
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (!hasVideo || width == null || height == null || width <= 0 || height <= 0) {
            ProbeResult.UNDECODABLE
        } else {
            ProbeResult(width, height, durationMs, true, rotation)
        }
    }
}
