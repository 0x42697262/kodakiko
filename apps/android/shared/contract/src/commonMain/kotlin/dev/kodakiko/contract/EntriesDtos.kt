package dev.kodakiko.contract

data class EntryDto(
    val id: String,
    val ownerId: String,
    val purpose: EntryPurpose,
    val revision: Long,
    val metadataBlob: String,
    val trashedAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class EntryMembershipRequest(val collectionId: String, val entryKeyByCollection: String)

data class EntryMediaRequest(val mediaId: String, val mediaKeyByEntry: String)

data class EntryCreateRequest(
    val id: String,
    val purpose: EntryPurpose,
    val metadataBlob: String,
    val memberships: List<EntryMembershipRequest>,
    val media: List<EntryMediaRequest>,
)

data class EntryPatchRequest(val metadataBlob: String, val baseRevision: Long)

data class RevisionConflictDetails(val revision: Long, val metadataBlob: String)

internal object EntriesCodecs {
    private val id = CommonCodecs.id
    private val bytes = CommonCodecs.bytes
    private val isoTime = CommonCodecs.isoTime
    private val revision: Codec<Long> = CommonCodecs.positive
    private val trashedAt: Codec<String?> = isoTime.nullable()

    val entryDto: Codec<EntryDto> = objectCodec(
        {
            EntryDto(
                id = field("id", id),
                ownerId = field("ownerId", id),
                purpose = field("purpose", CommonCodecs.entryPurpose),
                revision = field("revision", revision),
                metadataBlob = field("metadataBlob", bytes),
                trashedAt = field("trashedAt", trashedAt),
                createdAt = field("createdAt", isoTime),
                updatedAt = field("updatedAt", isoTime),
            )
        },
        {
            field("id", id, it.id)
            field("ownerId", id, it.ownerId)
            field("purpose", CommonCodecs.entryPurpose, it.purpose)
            field("revision", revision, it.revision)
            field("metadataBlob", bytes, it.metadataBlob)
            field("trashedAt", trashedAt, it.trashedAt)
            field("createdAt", isoTime, it.createdAt)
            field("updatedAt", isoTime, it.updatedAt)
        },
    )

    private val membership: Codec<EntryMembershipRequest> = objectCodec(
        { EntryMembershipRequest(field("collectionId", id), field("entryKeyByCollection", bytes)) },
        {
            field("collectionId", id, it.collectionId)
            field("entryKeyByCollection", bytes, it.entryKeyByCollection)
        },
    )

    private val mediaKey: Codec<EntryMediaRequest> = objectCodec(
        { EntryMediaRequest(field("mediaId", id), field("mediaKeyByEntry", bytes)) },
        {
            field("mediaId", id, it.mediaId)
            field("mediaKeyByEntry", bytes, it.mediaKeyByEntry)
        },
    )

    private val memberships: Codec<List<EntryMembershipRequest>> = membership.array(1, MEMBERSHIPS_PER_ENTRY_MAX)
    private val mediaKeys: Codec<List<EntryMediaRequest>> = mediaKey.array(1, MEDIA_PER_ENTRY_HARD_MAX)

    val entryCreateRequest: Codec<EntryCreateRequest> = objectCodec(
        {
            EntryCreateRequest(
                field("id", id),
                field("purpose", CommonCodecs.entryPurpose),
                field("metadataBlob", bytes),
                field("memberships", memberships),
                field("media", mediaKeys),
            )
        },
        {
            field("id", id, it.id)
            field("purpose", CommonCodecs.entryPurpose, it.purpose)
            field("metadataBlob", bytes, it.metadataBlob)
            field("memberships", memberships, it.memberships)
            field("media", mediaKeys, it.media)
        },
    )

    val entryPatchRequest: Codec<EntryPatchRequest> = objectCodec(
        { EntryPatchRequest(field("metadataBlob", bytes), field("baseRevision", revision)) },
        {
            field("metadataBlob", bytes, it.metadataBlob)
            field("baseRevision", revision, it.baseRevision)
        },
    )

    val revisionConflictDetails: Codec<RevisionConflictDetails> = objectCodec(
        { RevisionConflictDetails(field("revision", revision), field("metadataBlob", bytes)) },
        {
            field("revision", revision, it.revision)
            field("metadataBlob", bytes, it.metadataBlob)
        },
    )
}
