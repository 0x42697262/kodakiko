package dev.kodakiko.service.entries

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createStreamEncryptor
import dev.kodakiko.crypto.streamLayout
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.CaptureTimeInput
import dev.kodakiko.media.GeoPoint
import dev.kodakiko.media.ImageMetadata
import dev.kodakiko.media.MediaContainer
import dev.kodakiko.media.MediaFormat
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.SNIFF_HEADER_BYTES
import dev.kodakiko.media.TimezoneOffsetSource
import dev.kodakiko.media.VideoRotation
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.media.deviceOffsetMinutes
import dev.kodakiko.media.readImageMetadata
import dev.kodakiko.media.readVideoMetadata
import dev.kodakiko.media.resolveCaptureTime
import dev.kodakiko.media.sniffFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.Base64

interface ImportPipeline {
    suspend fun importOne(input: MediaInput, slots: ImportSlots): ImportOutcome

    suspend fun importVoiceNote(input: MediaInput, voice: VoiceNoteInfo, slots: ImportSlots): ImportOutcome
}

class ImportPipelineDeps(
    val sodium: Sodium,
    val files: EntryFiles,
    val toolkit: MediaToolkit,
    val clock: EntriesClock,
    val config: () -> PublicConfigDto?,
    val deviceSettings: DeviceSettingsSource,
    val randomId: () -> String,
    val ioDispatcher: CoroutineDispatcher,
)

private class Refusal(val reason: ImportRefusal) : Exception(reason.wire)

private class Embedded(
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val containerCreationUtc: String?,
    val gps: GeoPoint?,
    val rotation: VideoRotation,
)

private val NO_EMBEDDED = Embedded(null, null, null, null, VideoRotation.DEGREES_0)

private class PersistPlan(
    val source: ByteSource,
    val thumbnail: ThumbnailResult,
    val time: ResolvedTime,
    val kind: MediaKind,
    val mimeType: String,
    val width: Long,
    val height: Long,
    val durationMs: Long?,
    val originalFileName: String?,
    val location: ImportedLocation?,
)

private val voiceThumbnail: ByteArray by lazy { Base64.getDecoder().decode(ImportConstants.VOICE_THUMBNAIL_BASE64) }

private fun voiceThumbnailBytes(): ByteArray = voiceThumbnail.copyOf()

private fun fillFrom(file: ImageMetadata?, picker: ImageMetadata?): Embedded =
    Embedded(
        dateTimeOriginal = file?.dateTimeOriginal ?: picker?.dateTimeOriginal,
        offsetTimeOriginal = file?.offsetTimeOriginal ?: picker?.offsetTimeOriginal,
        containerCreationUtc = null,
        gps = file?.gps ?: picker?.gps,
        rotation = VideoRotation.DEGREES_0,
    )

private fun readEmbedded(source: ByteSource, format: MediaFormat, input: MediaInput): Embedded {
    val picker = input.pickerExif?.let(::pickerExifToMetadata)
    return when (format.container) {
        MediaContainer.JPEG, MediaContainer.PNG, MediaContainer.WEBP -> fillFrom(readImageMetadata(source, format.container), picker)
        MediaContainer.HEIF -> fillFrom(null, picker)
        MediaContainer.MP4, MediaContainer.MOV -> {
            val video = readVideoMetadata(source)
            Embedded(null, null, video.creationUtc, video.location, video.rotationDegrees)
        }
        MediaContainer.WEBM -> NO_EMBEDDED
    }
}

private fun posterTimeMs(format: MediaFormat, probe: ProbeResult): Long {
    if (format.kind != MediaKind.VIDEO) return ImportConstants.PHOTO_POSTER_TIME_MS
    return minOf(ImportConstants.POSTER_MAX_TIME_MS, (probe.durationMs ?: ImportConstants.NO_DURATION_MS) / ImportConstants.POSTER_DURATION_DIVISOR)
}

private fun isQuarterTurn(rotation: VideoRotation): Boolean = rotation == VideoRotation.DEGREES_90 || rotation == VideoRotation.DEGREES_270

class DefaultImportPipeline(private val deps: ImportPipelineDeps) : ImportPipeline {
    override suspend fun importOne(input: MediaInput, slots: ImportSlots): ImportOutcome =
        withContext(deps.ioDispatcher) {
            val job = coroutineContext.job
            settle(input) { build(input, slots, job) }
        }

    override suspend fun importVoiceNote(input: MediaInput, voice: VoiceNoteInfo, slots: ImportSlots): ImportOutcome =
        withContext(deps.ioDispatcher) {
            val job = coroutineContext.job
            settle(input) { buildVoice(input, voice, slots, job) }
        }

    private inline fun settle(input: MediaInput, run: () -> ImportedMedia): ImportOutcome =
        try {
            val media = run()
            discardQuietly(input)
            ImportOutcome.Imported(media)
        } catch (failure: CancellationException) {
            discardQuietly(input)
            throw failure
        } catch (refusal: Refusal) {
            discardQuietly(input)
            ImportOutcome.Refused(refusal.reason, input.fileName)
        } catch (_: Exception) {
            discardQuietly(input)
            ImportOutcome.Refused(ImportRefusal.READ_FAILED, input.fileName)
        }

    private fun build(input: MediaInput, slots: ImportSlots, job: Job): ImportedMedia {
        if (slots.used >= slots.limit) throw Refusal(ImportRefusal.TRAY_FULL)
        val maxUploadBytes = maxUploadBytes()
        val source = deps.toolkit.byteSource(input)
        val format = sniffFormat(source.read(0, SNIFF_HEADER_BYTES.toLong())) ?: throw Refusal(ImportRefusal.UNSUPPORTED)
        val probe = deps.toolkit.probe(input, format)
        if (!probe.decodable) throw Refusal(ImportRefusal.UNDECODABLE)
        if (ciphertextSizeOf(source.size) > maxUploadBytes) throw Refusal(ImportRefusal.TOO_LARGE)
        val camera = input.origin == MediaOrigin.CAMERA
        val embedded = readEmbedded(source, format, input)
        val time = resolveCaptureTime(
            CaptureTimeInput(
                exifDateTimeOriginal = if (camera) null else embedded.dateTimeOriginal,
                exifOffset = if (camera) null else embedded.offsetTimeOriginal,
                containerCreationUtc = if (camera) null else embedded.containerCreationUtc,
                fileModifiedUtc = if (camera) null else deps.toolkit.modifiedUtc(input),
                nowUtc = deps.clock.nowIso(),
                deviceOffsetMinutes = deviceOffset(),
            ),
        )
        val thumbnail = deps.toolkit.makeThumbnail(input, format, posterTimeMs(format, probe))
        val turned = isQuarterTurn(embedded.rotation)
        val gps = embedded.gps
        return persist(
            PersistPlan(
                source = source,
                thumbnail = thumbnail,
                time = time,
                kind = format.kind,
                mimeType = format.mimeType,
                width = (if (turned) probe.height else probe.width).toLong(),
                height = (if (turned) probe.width else probe.height).toLong(),
                durationMs = if (format.kind == MediaKind.VIDEO) probe.durationMs else null,
                originalFileName = if (camera) null else input.fileName,
                location = if (camera || gps == null) null else ImportedLocation(gps, LocationSource.EXIF),
            ),
            job,
        )
    }

    private fun buildVoice(input: MediaInput, voice: VoiceNoteInfo, slots: ImportSlots, job: Job): ImportedMedia {
        if (slots.used >= slots.limit) throw Refusal(ImportRefusal.TRAY_FULL)
        val source = deps.toolkit.byteSource(input)
        val time = resolveCaptureTime(
            CaptureTimeInput(
                exifDateTimeOriginal = null,
                exifOffset = null,
                containerCreationUtc = null,
                fileModifiedUtc = null,
                nowUtc = deps.clock.nowIso(),
                deviceOffsetMinutes = deviceOffset(),
            ),
        )
        val side = ImportConstants.VOICE_THUMBNAIL_SIZE
        return persist(
            PersistPlan(
                source = source,
                thumbnail = ThumbnailResult(voiceThumbnailBytes(), side, side),
                time = time,
                kind = MediaKind.AUDIO,
                mimeType = voice.mimeType,
                width = side.toLong(),
                height = side.toLong(),
                durationMs = voice.durationMs,
                originalFileName = null,
                location = null,
            ),
            job,
        )
    }

    private fun persist(plan: PersistPlan, job: Job): ImportedMedia {
        val source = plan.source
        val thumbnail = plan.thumbnail
        val originalCiphertextBytes = ciphertextSizeOf(source.size)
        if (originalCiphertextBytes > maxUploadBytes()) throw Refusal(ImportRefusal.TOO_LARGE)
        val mediaId = deps.randomId()
        val originalObjectId = deps.randomId()
        val thumbnailObjectId = deps.randomId()
        val mediaKey = deps.sodium.randomBytes(KEY_BYTES)
        val originalKey = variantKeyOf(mediaKey, KdfSubkeyIds.MEDIA_ORIGINAL)
        val thumbnailKey = variantKeyOf(mediaKey, KdfSubkeyIds.MEDIA_THUMBNAIL)
        try {
            deps.files.writeObject(originalObjectId, encryptedChunks(originalKey, source, job))
            deps.files.writeObject(thumbnailObjectId, encryptedChunks(thumbnailKey, byteSourceFromBytes(thumbnail.bytes), job))
            if (!encryptThumbnails()) deps.files.writePlainThumbnail(mediaId, thumbnail.bytes)
            val descriptor = validated(
                MediaDescriptorV1(
                    mediaId = mediaId,
                    kind = plan.kind,
                    mimeType = plan.mimeType,
                    width = plan.width,
                    height = plan.height,
                    durationMs = plan.durationMs,
                    originalPlaintextBytes = source.size,
                    thumbnail = ThumbnailDescriptor(
                        width = thumbnail.width.toLong(),
                        height = thumbnail.height.toLong(),
                        plaintextBytes = thumbnail.bytes.size.toLong(),
                    ),
                    capturedAtUtc = plan.time.capturedAtUtc,
                    originalFileName = plan.originalFileName,
                ),
            )
            return ImportedMedia(
                mediaId = mediaId,
                mediaKey = mediaKey,
                originalObjectId = originalObjectId,
                thumbnailObjectId = thumbnailObjectId,
                originalCiphertextBytes = originalCiphertextBytes,
                thumbnailCiphertextBytes = ciphertextSizeOf(thumbnail.bytes.size.toLong()),
                descriptor = descriptor,
                time = plan.time,
                location = plan.location,
                thumbnailBytes = thumbnail.bytes,
            )
        } catch (failure: Throwable) {
            deps.files.deleteMediaFilesQuietly(mediaId, originalObjectId, thumbnailObjectId)
            wipe(mediaKey)
            throw failure
        } finally {
            wipe(originalKey)
            wipe(thumbnailKey)
        }
    }

    private fun encryptedChunks(variantKey: ByteArray, source: ByteSource, job: Job): Sequence<ByteArray> =
        sequence {
            val encryptor = createStreamEncryptor(deps.sodium, variantKey, source.size)
            yield(encryptor.header)
            val layout = encryptor.layout
            for (index in 0 until layout.chunkCount) {
                job.ensureActive()
                val plaintext = source.read(index * layout.chunkSize, layout.chunkSize.toLong())
                val ciphertext = try {
                    encryptor.encryptNext(plaintext)
                } finally {
                    wipe(plaintext)
                }
                yield(ciphertext)
            }
        }

    private fun discardQuietly(input: MediaInput) {
        try {
            deps.toolkit.discard(input)
        } catch (_: Exception) {
            return
        }
    }

    private fun variantKeyOf(mediaKey: ByteArray, subkeyId: Long): ByteArray = deps.sodium.kdfDerive(KEY_BYTES, subkeyId, KdfContext.MEDIA.value, mediaKey)

    private fun maxUploadBytes(): Long = deps.config()?.maxUploadBytes ?: EntriesConstants.FALLBACK_MAX_UPLOAD_BYTES

    private fun encryptThumbnails(): Boolean = !deps.deviceSettings.loaded() || deps.deviceSettings.get().encryptThumbnails

    private fun deviceOffset(): Long = deviceOffsetMinutes(TimezoneOffsetSource { deps.clock.timezoneOffsetMinutes(deps.clock.now()) })

    private fun validated(descriptor: MediaDescriptorV1): MediaDescriptorV1 =
        ContractSchemas.mediaDescriptorV1.decode(ContractSchemas.mediaDescriptorV1.encode(descriptor))
}

private fun ciphertextSizeOf(plaintextSize: Long): Long = streamLayout(plaintextSize, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize
