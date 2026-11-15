package dev.kodakiko.service.links

import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.ImageMetadata
import dev.kodakiko.media.MediaContainer
import dev.kodakiko.media.STRIP_COPY_CHUNK_BYTES
import dev.kodakiko.media.StripSegment
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.media.hasExifSegment
import dev.kodakiko.media.moovHasMetadata
import dev.kodakiko.media.planIsoBmffStrip
import dev.kodakiko.media.planWebmDateStrip
import dev.kodakiko.media.readImageMetadata
import dev.kodakiko.media.readVideoMetadata
import dev.kodakiko.media.streamStripped
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream

class StrippedMedia(
    val chunks: Sequence<ByteArray>,
    val plaintextSize: Long,
    val mimeType: String,
    val width: Long,
    val height: Long,
    val durationMs: Long?,
)

interface LinkStripper {
    suspend fun strip(source: ByteSource, descriptor: MediaDescriptorV1): StrippedMedia
}

class ReencodedPhoto(val bytes: ByteArray, val width: Int, val height: Int)

fun interface PhotoReencoder {
    suspend fun reencode(bytes: ByteArray, mimeType: String): ReencodedPhoto
}

class DefaultLinkStripper(private val reencoder: PhotoReencoder) : LinkStripper {
    override suspend fun strip(source: ByteSource, descriptor: MediaDescriptorV1): StrippedMedia =
        if (descriptor.kind == MediaKind.PHOTO) stripPhoto(source, descriptor) else stripVideo(source, descriptor)

    private suspend fun stripPhoto(source: ByteSource, descriptor: MediaDescriptorV1): StrippedMedia {
        val original = source.read(0, source.size)
        val photo = try {
            reencoder.reencode(original, descriptor.mimeType)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw StripUndecodableException()
        }
        val metadata = metadataOrNull(photo.bytes)
        val leaks = metadata != null && (metadata.dateTimeOriginal != null || metadata.offsetTimeOriginal != null || metadata.gps != null)
        if (leaks || hasExifSegment(photo.bytes)) throw StripFailedException()
        return StrippedMedia(
            chunks = sequenceOf(photo.bytes),
            plaintextSize = photo.bytes.size.toLong(),
            mimeType = LinksConstants.PHOTO_MIME,
            width = photo.width.toLong(),
            height = photo.height.toLong(),
            durationMs = null,
        )
    }

    private fun stripVideo(source: ByteSource, descriptor: MediaDescriptorV1): StrippedMedia {
        val webm = descriptor.mimeType == LinksConstants.WEBM_MIME
        val plan = if (webm) planWebmDateStrip(source) else planIsoBmffStrip(source)
        if (!webm && moovHasMetadata(plan.moov)) throw StripFailedException()
        if (!webm) {
            val head = collect(streamStripped(source, plan.segments.filterIsInstance<StripSegment.Bytes>(), STRIP_COPY_CHUNK_BYTES))
            val check = readVideoMetadata(byteSourceFromBytes(head))
            if (check.creationUtc != null || check.location != null) throw StripFailedException()
        }
        return StrippedMedia(
            chunks = streamStripped(source, plan.segments, STRIP_COPY_CHUNK_BYTES),
            plaintextSize = plan.outputSize,
            mimeType = descriptor.mimeType,
            width = descriptor.width,
            height = descriptor.height,
            durationMs = descriptor.durationMs,
        )
    }

    private fun metadataOrNull(bytes: ByteArray): ImageMetadata? =
        try {
            readImageMetadata(byteSourceFromBytes(bytes), MediaContainer.JPEG)
        } catch (_: Exception) {
            null
        }

    private fun collect(chunks: Sequence<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        chunks.forEach(output::write)
        return output.toByteArray()
    }
}
