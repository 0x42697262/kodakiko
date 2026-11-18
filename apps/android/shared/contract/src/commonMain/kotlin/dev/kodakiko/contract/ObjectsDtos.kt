package dev.kodakiko.contract

import kotlinx.serialization.json.JsonElement

data class PresignRequest(val id: String, val mediaId: String, val variant: ObjectVariant, val sizeBytes: Long)

data class PartUrl(val partNumber: Long, val url: String)

sealed interface PresignResponse {
    val expiresAt: String

    data class Single(val url: String, override val expiresAt: String) : PresignResponse

    data class Multipart(val partSizeBytes: Long, val partUrls: List<PartUrl>, override val expiresAt: String) : PresignResponse
}

data class UploadedPart(val partNumber: Long, val sizeBytes: Long)

data class PartsResponse(val partSizeBytes: Long, val uploadedParts: List<UploadedPart>, val partUrls: List<PartUrl>, val expiresAt: String)

data class ObjectDto(val id: String, val mediaId: String, val variant: ObjectVariant, val sizeBytes: Long, val state: ObjectState)

data class DownloadResponse(val url: String, val expiresAt: String)

internal object ObjectsCodecs {
    private const val MODE = "mode"
    private const val MODE_SINGLE = "single"
    private const val MODE_MULTIPART = "multipart"
    private val objectSize: Codec<Long> = IntegerCodec(minimum = MIN_OBJECT_BYTES)
    private val partNumber: Codec<Long> = CommonCodecs.positive
    private val id = CommonCodecs.id
    private val isoTime = CommonCodecs.isoTime

    val presignRequest: Codec<PresignRequest> = objectCodec(
        { PresignRequest(field("id", id), field("mediaId", id), field("variant", CommonCodecs.objectVariant), field("sizeBytes", objectSize)) },
        {
            field("id", id, it.id)
            field("mediaId", id, it.mediaId)
            field("variant", CommonCodecs.objectVariant, it.variant)
            field("sizeBytes", objectSize, it.sizeBytes)
        },
    )

    private val partUrl: Codec<PartUrl> = objectCodec(
        { PartUrl(field("partNumber", partNumber), field("url", CommonCodecs.url)) },
        {
            field("partNumber", partNumber, it.partNumber)
            field("url", CommonCodecs.url, it.url)
        },
    )

    private val partUrls: Codec<List<PartUrl>> = partUrl.array()
    private val singleMode: Codec<Unit> = StringLiteralCodec(MODE_SINGLE)
    private val multipartMode: Codec<Unit> = StringLiteralCodec(MODE_MULTIPART)

    private val single: Codec<PresignResponse.Single> = objectCodec(
        {
            field(MODE, singleMode)
            PresignResponse.Single(field("url", CommonCodecs.url), field("expiresAt", isoTime))
        },
        {
            field(MODE, singleMode, Unit)
            field("url", CommonCodecs.url, it.url)
            field("expiresAt", isoTime, it.expiresAt)
        },
    )

    private val multipart: Codec<PresignResponse.Multipart> = objectCodec(
        {
            field(MODE, multipartMode)
            PresignResponse.Multipart(field("partSizeBytes", CommonCodecs.positive), field("partUrls", partUrls), field("expiresAt", isoTime))
        },
        {
            field(MODE, multipartMode, Unit)
            field("partSizeBytes", CommonCodecs.positive, it.partSizeBytes)
            field("partUrls", partUrls, it.partUrls)
            field("expiresAt", isoTime, it.expiresAt)
        },
    )

    val presignResponse: Codec<PresignResponse> = DiscriminatedCodec(
        MODE,
        mapOf(MODE_SINGLE to single, MODE_MULTIPART to multipart),
        ::encodePresign,
    )

    private fun encodePresign(value: PresignResponse): JsonElement =
        when (value) {
            is PresignResponse.Single -> single.encodeRequired(value)
            is PresignResponse.Multipart -> multipart.encodeRequired(value)
        }

    private val uploadedPart: Codec<UploadedPart> = objectCodec(
        { UploadedPart(field("partNumber", partNumber), field("sizeBytes", CommonCodecs.count)) },
        {
            field("partNumber", partNumber, it.partNumber)
            field("sizeBytes", CommonCodecs.count, it.sizeBytes)
        },
    )

    private val uploadedParts: Codec<List<UploadedPart>> = uploadedPart.array()

    val partsResponse: Codec<PartsResponse> = objectCodec(
        {
            PartsResponse(
                field("partSizeBytes", CommonCodecs.positive),
                field("uploadedParts", uploadedParts),
                field("partUrls", partUrls),
                field("expiresAt", isoTime),
            )
        },
        {
            field("partSizeBytes", CommonCodecs.positive, it.partSizeBytes)
            field("uploadedParts", uploadedParts, it.uploadedParts)
            field("partUrls", partUrls, it.partUrls)
            field("expiresAt", isoTime, it.expiresAt)
        },
    )

    fun ObjectReader.readObject(): ObjectDto =
        ObjectDto(
            field("id", id),
            field("mediaId", id),
            field("variant", CommonCodecs.objectVariant),
            field("sizeBytes", objectSize),
            field("state", CommonCodecs.objectState),
        )

    fun ObjectWriter.writeObject(value: ObjectDto) {
        field("id", id, value.id)
        field("mediaId", id, value.mediaId)
        field("variant", CommonCodecs.objectVariant, value.variant)
        field("sizeBytes", objectSize, value.sizeBytes)
        field("state", CommonCodecs.objectState, value.state)
    }

    val objectDto: Codec<ObjectDto> = objectCodec({ readObject() }, { writeObject(it) })

    val downloadResponse: Codec<DownloadResponse> = objectCodec(
        { DownloadResponse(field("url", CommonCodecs.url), field("expiresAt", isoTime)) },
        {
            field("url", CommonCodecs.url, it.url)
            field("expiresAt", isoTime, it.expiresAt)
        },
    )
}
