package dev.kodakiko.contract

data class DiffQuery(val since: Long, val limit: Long? = null)

data class DiffMembership(val entryId: String, val entryKeyByCollection: String?, val seq: Long, val removed: Boolean)

data class DiffPage(
    val collectionId: String,
    val memberships: List<DiffMembership>,
    val entries: List<EntryDto>,
    val media: List<MediaDto>,
    val objects: List<ObjectDto>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val resyncRequired: Boolean,
)

data class SyncCollectionState(
    val id: String,
    val kind: CollectionKind,
    val role: CollectionRole,
    val lastSeq: Long,
    val tombstonesPrunedThroughSeq: Long,
    val metaUpdatedAt: String,
)

data class SyncStateResponse(val collections: List<SyncCollectionState>)

internal object SyncCodecs {
    private val id = CommonCodecs.id
    private val count = CommonCodecs.count
    private val since: Codec<Long> = IntegerCodec(minimum = 0, coerce = true)
    private val entryKey: Codec<String?> = CommonCodecs.bytes.nullable()

    val diffQuery: Codec<DiffQuery> = objectCodec(
        { DiffQuery(field("since", since), field("limit", CommonCodecs.queryLimit)) },
        {
            field("since", since, it.since)
            field("limit", CommonCodecs.queryLimit, it.limit)
        },
    )

    private val membership: Codec<DiffMembership> = objectCodec(
        {
            DiffMembership(
                field("entryId", id),
                field("entryKeyByCollection", entryKey),
                field("seq", count),
                field("removed", CommonCodecs.boolean),
            )
        },
        {
            field("entryId", id, it.entryId)
            field("entryKeyByCollection", entryKey, it.entryKeyByCollection)
            field("seq", count, it.seq)
            field("removed", CommonCodecs.boolean, it.removed)
        },
    )

    private val memberships: Codec<List<DiffMembership>> = membership.array()
    private val entries: Codec<List<EntryDto>> = EntriesCodecs.entryDto.array()
    private val media: Codec<List<MediaDto>> = MediaCodecs.mediaDto.array()
    private val objects: Codec<List<ObjectDto>> = ObjectsCodecs.objectDto.array()

    val diffPage: Codec<DiffPage> = objectCodec(
        {
            DiffPage(
                collectionId = field("collectionId", id),
                memberships = field("memberships", memberships),
                entries = field("entries", entries),
                media = field("media", media),
                objects = field("objects", objects),
                nextCursor = field("nextCursor", count),
                hasMore = field("hasMore", CommonCodecs.boolean),
                resyncRequired = field("resyncRequired", CommonCodecs.boolean),
            )
        },
        {
            field("collectionId", id, it.collectionId)
            field("memberships", memberships, it.memberships)
            field("entries", entries, it.entries)
            field("media", media, it.media)
            field("objects", objects, it.objects)
            field("nextCursor", count, it.nextCursor)
            field("hasMore", CommonCodecs.boolean, it.hasMore)
            field("resyncRequired", CommonCodecs.boolean, it.resyncRequired)
        },
    )

    private val collectionState: Codec<SyncCollectionState> = objectCodec(
        {
            SyncCollectionState(
                id = field("id", id),
                kind = field("kind", CommonCodecs.collectionKind),
                role = field("role", CommonCodecs.collectionRole),
                lastSeq = field("lastSeq", count),
                tombstonesPrunedThroughSeq = field("tombstonesPrunedThroughSeq", count),
                metaUpdatedAt = field("metaUpdatedAt", CommonCodecs.isoTime),
            )
        },
        {
            field("id", id, it.id)
            field("kind", CommonCodecs.collectionKind, it.kind)
            field("role", CommonCodecs.collectionRole, it.role)
            field("lastSeq", count, it.lastSeq)
            field("tombstonesPrunedThroughSeq", count, it.tombstonesPrunedThroughSeq)
            field("metaUpdatedAt", CommonCodecs.isoTime, it.metaUpdatedAt)
        },
    )

    private val collectionStates: Codec<List<SyncCollectionState>> = collectionState.array()

    val syncStateResponse: Codec<SyncStateResponse> = objectCodec(
        { SyncStateResponse(field("collections", collectionStates)) },
        { field("collections", collectionStates, it.collections) },
    )
}
