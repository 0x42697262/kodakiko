package dev.kodakiko.contract

import kotlinx.serialization.json.JsonElement

data class PublicLinkCreateRequest(val collectionId: String, val accessToken: String, val expiry: LinkExpiry)

data class PublicLinkDto(
    val linkId: String,
    val collectionId: String,
    val expiresAt: String?,
    val maxViews: Long?,
    val viewCount: Long,
    val createdAt: String,
)

data class PublicLinkListResponse(val items: List<PublicLinkDto>)

data class PublicLinkParams(val linkId: String)

data class PublicLinkObjectParams(val linkId: String, val objectId: String)

data class LinkOpenResponse(
    val collectionId: String,
    val metadataBlob: String,
    val expiresAt: String?,
    val maxViews: Long?,
    val viewToken: String,
)

sealed interface LinkDownload {
    data class Presigned(val url: String, val expiresAt: String) : LinkDownload

    data class Proxy(val path: String) : LinkDownload
}

data class LinkObjectDto(
    val id: String,
    val mediaId: String,
    val variant: ObjectVariant,
    val sizeBytes: Long,
    val state: ObjectState,
    val download: LinkDownload,
)

data class LinkEntryItem(val entry: EntryDto, val entryKeyByCollection: String, val media: List<MediaDto>, val objects: List<LinkObjectDto>)

data class LinkEntriesResponse(val items: List<LinkEntryItem>, val nextCursor: String?)

internal object LinksCodecs {
    private const val KIND = "kind"
    private const val KIND_PRESIGNED = "presigned"
    private const val KIND_PROXY = "proxy"
    private const val PROXY_PATH_PREFIX = "/"
    private val id = CommonCodecs.id
    private val bytes = CommonCodecs.bytes
    private val isoTime = CommonCodecs.isoTime
    private val nullableIsoTime: Codec<String?> = isoTime.nullable()
    private val maxViews: Codec<Long?> = CommonCodecs.positive.nullable()

    val publicLinkCreateRequest: Codec<PublicLinkCreateRequest> = objectCodec(
        { PublicLinkCreateRequest(field("collectionId", id), field("accessToken", bytes), field("expiry", CommonCodecs.linkExpiry)) },
        {
            field("collectionId", id, it.collectionId)
            field("accessToken", bytes, it.accessToken)
            field("expiry", CommonCodecs.linkExpiry, it.expiry)
        },
    )

    val publicLinkDto: Codec<PublicLinkDto> = objectCodec(
        {
            PublicLinkDto(
                linkId = field("linkId", id),
                collectionId = field("collectionId", id),
                expiresAt = field("expiresAt", nullableIsoTime),
                maxViews = field("maxViews", maxViews),
                viewCount = field("viewCount", CommonCodecs.count),
                createdAt = field("createdAt", isoTime),
            )
        },
        {
            field("linkId", id, it.linkId)
            field("collectionId", id, it.collectionId)
            field("expiresAt", nullableIsoTime, it.expiresAt)
            field("maxViews", maxViews, it.maxViews)
            field("viewCount", CommonCodecs.count, it.viewCount)
            field("createdAt", isoTime, it.createdAt)
        },
    )

    private val publicLinkItems: Codec<List<PublicLinkDto>> = publicLinkDto.array()

    val publicLinkListResponse: Codec<PublicLinkListResponse> = objectCodec(
        { PublicLinkListResponse(field("items", publicLinkItems)) },
        { field("items", publicLinkItems, it.items) },
    )

    val publicLinkParams: Codec<PublicLinkParams> = objectCodec(
        { PublicLinkParams(field("linkId", id)) },
        { field("linkId", id, it.linkId) },
    )

    val publicLinkObjectParams: Codec<PublicLinkObjectParams> = objectCodec(
        { PublicLinkObjectParams(field("linkId", id), field("objectId", id)) },
        {
            field("linkId", id, it.linkId)
            field("objectId", id, it.objectId)
        },
    )

    val linkOpenResponse: Codec<LinkOpenResponse> = objectCodec(
        {
            LinkOpenResponse(
                collectionId = field("collectionId", id),
                metadataBlob = field("metadataBlob", bytes),
                expiresAt = field("expiresAt", nullableIsoTime),
                maxViews = field("maxViews", maxViews),
                viewToken = field("viewToken", CommonCodecs.nonEmptyText),
            )
        },
        {
            field("collectionId", id, it.collectionId)
            field("metadataBlob", bytes, it.metadataBlob)
            field("expiresAt", nullableIsoTime, it.expiresAt)
            field("maxViews", maxViews, it.maxViews)
            field("viewToken", CommonCodecs.nonEmptyText, it.viewToken)
        },
    )

    private val presignedKind: Codec<Unit> = StringLiteralCodec(KIND_PRESIGNED)
    private val proxyKind: Codec<Unit> = StringLiteralCodec(KIND_PROXY)
    private val proxyPath: Codec<String> = StringCodec(prefix = PROXY_PATH_PREFIX)

    private val presigned: Codec<LinkDownload.Presigned> = objectCodec(
        {
            field(KIND, presignedKind)
            LinkDownload.Presigned(field("url", CommonCodecs.url), field("expiresAt", isoTime))
        },
        {
            field(KIND, presignedKind, Unit)
            field("url", CommonCodecs.url, it.url)
            field("expiresAt", isoTime, it.expiresAt)
        },
    )

    private val proxy: Codec<LinkDownload.Proxy> = objectCodec(
        {
            field(KIND, proxyKind)
            LinkDownload.Proxy(field("path", proxyPath))
        },
        {
            field(KIND, proxyKind, Unit)
            field("path", proxyPath, it.path)
        },
    )

    val linkDownload: Codec<LinkDownload> = DiscriminatedCodec(
        KIND,
        mapOf(KIND_PRESIGNED to presigned, KIND_PROXY to proxy),
        ::encodeDownload,
    )

    private fun encodeDownload(value: LinkDownload): JsonElement =
        when (value) {
            is LinkDownload.Presigned -> presigned.encodeRequired(value)
            is LinkDownload.Proxy -> proxy.encodeRequired(value)
        }

    val linkObjectDto: Codec<LinkObjectDto> = objectCodec(
        {
            val base = with(ObjectsCodecs) { readObject() }
            LinkObjectDto(base.id, base.mediaId, base.variant, base.sizeBytes, base.state, field("download", linkDownload))
        },
        {
            with(ObjectsCodecs) { writeObject(ObjectDto(it.id, it.mediaId, it.variant, it.sizeBytes, it.state)) }
            field("download", linkDownload, it.download)
        },
    )

    private val linkObjects: Codec<List<LinkObjectDto>> = linkObjectDto.array()
    private val media: Codec<List<MediaDto>> = MediaCodecs.mediaDto.array()

    val linkEntryItem: Codec<LinkEntryItem> = objectCodec(
        {
            LinkEntryItem(
                field("entry", EntriesCodecs.entryDto),
                field("entryKeyByCollection", bytes),
                field("media", media),
                field("objects", linkObjects),
            )
        },
        {
            field("entry", EntriesCodecs.entryDto, it.entry)
            field("entryKeyByCollection", bytes, it.entryKeyByCollection)
            field("media", media, it.media)
            field("objects", linkObjects, it.objects)
        },
    )

    private val linkEntryItems: Codec<List<LinkEntryItem>> = linkEntryItem.array()
    private val nextCursor: Codec<String?> = CommonCodecs.text.nullable()

    val linkEntriesResponse: Codec<LinkEntriesResponse> = objectCodec(
        { LinkEntriesResponse(field("items", linkEntryItems), field("nextCursor", nextCursor)) },
        {
            field("items", linkEntryItems, it.items)
            field("nextCursor", nextCursor, it.nextCursor)
        },
    )
}
