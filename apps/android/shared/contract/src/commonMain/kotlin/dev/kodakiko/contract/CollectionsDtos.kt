package dev.kodakiko.contract

data class CollectionDto(
    val id: String,
    val kind: CollectionKind,
    val role: CollectionRole,
    val ownerId: String,
    val ownerUsername: String,
    val keyMaterial: String,
    val metadataBlob: String,
    val lastSeq: Long,
    val createdAt: String,
    val metaUpdatedAt: String,
    val recipientCount: Long? = null,
)

data class CollectionListResponse(val items: List<CollectionDto>, val nextCursor: String?)

data class CollectionCreateRequest(val id: String, val kind: CollectionKind, val keyByOwner: String, val metadataBlob: String)

data class CollectionEntryAddRequest(val entryId: String, val entryKeyByCollection: String)

data class CollectionEntryParams(val id: String, val entryId: String)

data class ShareCreateRequest(val recipientUserId: String, val sealedCollectionKey: String)

data class ShareParams(val id: String, val recipientUserId: String)

data class ShareDto(val recipientUserId: String, val recipientUsername: String, val createdAt: String)

data class ShareListResponse(val items: List<ShareDto>)

data class CollectionPatchRequest(val metadataBlob: String)

internal object CollectionsCodecs {
    private val id = CommonCodecs.id
    private val bytes = CommonCodecs.bytes
    private val isoTime = CommonCodecs.isoTime
    private val recipientCount: Codec<Long?> = CommonCodecs.count.defaultNull()
    private val nextCursor: Codec<String?> = CommonCodecs.text.nullable()
    private val createKind: Codec<CollectionKind> = enumCodec(listOf(CollectionKind.SHARED, CollectionKind.LINK))

    val collectionDto: Codec<CollectionDto> = objectCodec(
        {
            CollectionDto(
                id = field("id", id),
                kind = field("kind", CommonCodecs.collectionKind),
                role = field("role", CommonCodecs.collectionRole),
                ownerId = field("ownerId", id),
                ownerUsername = field("ownerUsername", CommonCodecs.text),
                keyMaterial = field("keyMaterial", bytes),
                metadataBlob = field("metadataBlob", bytes),
                lastSeq = field("lastSeq", CommonCodecs.count),
                createdAt = field("createdAt", isoTime),
                metaUpdatedAt = field("metaUpdatedAt", isoTime),
                recipientCount = field("recipientCount", recipientCount),
            )
        },
        {
            field("id", id, it.id)
            field("kind", CommonCodecs.collectionKind, it.kind)
            field("role", CommonCodecs.collectionRole, it.role)
            field("ownerId", id, it.ownerId)
            field("ownerUsername", CommonCodecs.text, it.ownerUsername)
            field("keyMaterial", bytes, it.keyMaterial)
            field("metadataBlob", bytes, it.metadataBlob)
            field("lastSeq", CommonCodecs.count, it.lastSeq)
            field("createdAt", isoTime, it.createdAt)
            field("metaUpdatedAt", isoTime, it.metaUpdatedAt)
            field("recipientCount", recipientCount, it.recipientCount)
        },
    )

    private val collectionItems: Codec<List<CollectionDto>> = collectionDto.array()

    val collectionListResponse: Codec<CollectionListResponse> = objectCodec(
        { CollectionListResponse(field("items", collectionItems), field("nextCursor", nextCursor)) },
        {
            field("items", collectionItems, it.items)
            field("nextCursor", nextCursor, it.nextCursor)
        },
    )

    val collectionCreateRequest: Codec<CollectionCreateRequest> = objectCodec(
        { CollectionCreateRequest(field("id", id), field("kind", createKind), field("keyByOwner", bytes), field("metadataBlob", bytes)) },
        {
            field("id", id, it.id)
            field("kind", createKind, it.kind)
            field("keyByOwner", bytes, it.keyByOwner)
            field("metadataBlob", bytes, it.metadataBlob)
        },
    )

    val collectionEntryAddRequest: Codec<CollectionEntryAddRequest> = objectCodec(
        { CollectionEntryAddRequest(field("entryId", id), field("entryKeyByCollection", bytes)) },
        {
            field("entryId", id, it.entryId)
            field("entryKeyByCollection", bytes, it.entryKeyByCollection)
        },
    )

    val collectionEntryParams: Codec<CollectionEntryParams> = objectCodec(
        { CollectionEntryParams(field("id", id), field("entryId", id)) },
        {
            field("id", id, it.id)
            field("entryId", id, it.entryId)
        },
    )

    val shareCreateRequest: Codec<ShareCreateRequest> = objectCodec(
        { ShareCreateRequest(field("recipientUserId", id), field("sealedCollectionKey", bytes)) },
        {
            field("recipientUserId", id, it.recipientUserId)
            field("sealedCollectionKey", bytes, it.sealedCollectionKey)
        },
    )

    val shareParams: Codec<ShareParams> = objectCodec(
        { ShareParams(field("id", id), field("recipientUserId", id)) },
        {
            field("id", id, it.id)
            field("recipientUserId", id, it.recipientUserId)
        },
    )

    val shareDto: Codec<ShareDto> = objectCodec(
        { ShareDto(field("recipientUserId", id), field("recipientUsername", CommonCodecs.text), field("createdAt", isoTime)) },
        {
            field("recipientUserId", id, it.recipientUserId)
            field("recipientUsername", CommonCodecs.text, it.recipientUsername)
            field("createdAt", isoTime, it.createdAt)
        },
    )

    private val shareItems: Codec<List<ShareDto>> = shareDto.array()

    val shareListResponse: Codec<ShareListResponse> = objectCodec(
        { ShareListResponse(field("items", shareItems)) },
        { field("items", shareItems, it.items) },
    )

    val collectionPatchRequest: Codec<CollectionPatchRequest> = objectCodec(
        { CollectionPatchRequest(field("metadataBlob", bytes)) },
        { field("metadataBlob", bytes, it.metadataBlob) },
    )
}
