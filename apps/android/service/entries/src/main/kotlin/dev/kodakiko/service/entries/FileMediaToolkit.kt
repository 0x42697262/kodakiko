package dev.kodakiko.service.entries

import android.content.Context
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.media.PlatformProbe
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailEncoder
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.js.JsDate
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import dev.kodakiko.data.media.MediaKind as PlatformMediaKind

class FileMediaToolkit(private val platform: PlatformMedia) : MediaToolkit {
    private val handles = ConcurrentHashMap<String, RandomAccessFile>()

    override fun byteSource(input: MediaInput): ByteSource {
        val path = input.file.path
        closeHandle(path)
        val handle = RandomAccessFile(input.file, ImportConstants.READ_ONLY_MODE)
        handles[path] = handle
        return RandomAccessByteSource(handle)
    }

    override fun probe(input: MediaInput, format: MediaFormat): ProbeResult =
        try {
            platform.probe(input.file, platformKindOf(format))
        } catch (_: Exception) {
            ProbeResult.UNDECODABLE
        }

    override fun makeThumbnail(input: MediaInput, format: MediaFormat, posterTimeMs: Long): ThumbnailResult =
        platform.thumbnail(input.file, platformKindOf(format), posterTimeMs)

    override fun modifiedUtc(input: MediaInput): String? =
        if (input.file.exists()) JsDate.toIsoString(input.file.lastModified().toDouble()) else null

    override fun discard(input: MediaInput) {
        closeHandle(input.file.path)
        if (input.file.exists()) input.file.delete()
    }

    private fun closeHandle(path: String) {
        handles.remove(path)?.close()
    }

    private fun platformKindOf(format: MediaFormat): PlatformMediaKind =
        if (format.kind == MediaKind.VIDEO) PlatformMediaKind.VIDEO else PlatformMediaKind.PHOTO
}

private class RandomAccessByteSource(private val handle: RandomAccessFile) : ByteSource {
    override val size: Long = handle.length()

    override fun read(offset: Long, length: Long): ByteArray =
        synchronized(handle) {
            val start = offset.coerceIn(0L, size)
            val bytes = ByteArray(Math.toIntExact(minOf(maxOf(0L, length), size - start)))
            handle.seek(start)
            handle.readFully(bytes)
            bytes
        }
}

class DevicePlatformMedia(context: Context) : PlatformMedia {
    private val prober = PlatformProbe(context)
    private val encoder = ThumbnailEncoder(context)

    override fun probe(file: File, kind: PlatformMediaKind): ProbeResult = prober.probe(file, kind)

    override fun thumbnail(file: File, kind: PlatformMediaKind, posterTimeMs: Long): ThumbnailResult =
        when (kind) {
            PlatformMediaKind.PHOTO -> encoder.photo(file)
            PlatformMediaKind.VIDEO -> encoder.video(file, posterTimeMs)
        }
}
