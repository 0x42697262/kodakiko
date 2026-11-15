package dev.kodakiko.service.entries

import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.WireEnum
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.GeoPoint
import dev.kodakiko.media.MediaFormat
import dev.kodakiko.media.ResolvedTime
import kotlinx.serialization.json.JsonObject
import java.io.File
import dev.kodakiko.data.media.MediaKind as PlatformMediaKind

enum class MediaOrigin(override val wire: String) : WireEnum {
    CAMERA("camera"),
    PICKER("picker"),
    DROP("drop"),
    PASTE("paste"),
}

data class MediaInput(val origin: MediaOrigin, val file: File, val fileName: String?, val pickerExif: JsonObject?)

data class ImportedLocation(val point: GeoPoint, val source: LocationSource)

class ImportedMedia(
    val mediaId: String,
    val mediaKey: ByteArray,
    val originalObjectId: String,
    val thumbnailObjectId: String,
    val originalCiphertextBytes: Long,
    val thumbnailCiphertextBytes: Long,
    val descriptor: MediaDescriptorV1,
    val time: ResolvedTime,
    val location: ImportedLocation?,
    val thumbnailBytes: ByteArray,
) {
    override fun toString(): String = "ImportedMedia(mediaId=$mediaId)"
}

data class ImportSlots(val used: Int, val limit: Int)

enum class ImportRefusal(override val wire: String) : WireEnum {
    UNSUPPORTED("unsupported"),
    UNDECODABLE("undecodable"),
    TOO_LARGE("tooLarge"),
    TRAY_FULL("trayFull"),
    READ_FAILED("readFailed"),
}

sealed interface ImportOutcome {
    data class Imported(val media: ImportedMedia) : ImportOutcome

    data class Refused(val reason: ImportRefusal, val fileName: String?) : ImportOutcome
}

enum class ReplaceResult { REPLACED, REFUSED }

data class VoiceNoteInfo(val durationMs: Long, val mimeType: String)

interface MediaToolkit {
    fun byteSource(input: MediaInput): ByteSource

    fun probe(input: MediaInput, format: MediaFormat): ProbeResult

    fun makeThumbnail(input: MediaInput, format: MediaFormat, posterTimeMs: Long): ThumbnailResult

    fun modifiedUtc(input: MediaInput): String?

    fun discard(input: MediaInput)
}

interface PlatformMedia {
    fun probe(file: File, kind: PlatformMediaKind): ProbeResult

    fun thumbnail(file: File, kind: PlatformMediaKind, posterTimeMs: Long): ThumbnailResult
}
