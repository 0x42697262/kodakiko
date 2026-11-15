package dev.kodakiko.contract

data class MediaDto(val id: String, val entryId: String, val mediaKeyByEntry: String, val createdAt: String)

data class MediaCreateRequest(val mediaId: String, val mediaKeyByEntry: String)

internal object MediaCodecs {
    private val id = CommonCodecs.id
    private val bytes = CommonCodecs.bytes
    private val isoTime = CommonCodecs.isoTime

    val mediaDto: Codec<MediaDto> = objectCodec(
        { MediaDto(field("id", id), field("entryId", id), field("mediaKeyByEntry", bytes), field("createdAt", isoTime)) },
        {
            field("id", id, it.id)
            field("entryId", id, it.entryId)
            field("mediaKeyByEntry", bytes, it.mediaKeyByEntry)
            field("createdAt", isoTime, it.createdAt)
        },
    )

    val mediaCreateRequest: Codec<MediaCreateRequest> = objectCodec(
        { MediaCreateRequest(field("mediaId", id), field("mediaKeyByEntry", bytes)) },
        {
            field("mediaId", id, it.mediaId)
            field("mediaKeyByEntry", bytes, it.mediaKeyByEntry)
        },
    )
}
