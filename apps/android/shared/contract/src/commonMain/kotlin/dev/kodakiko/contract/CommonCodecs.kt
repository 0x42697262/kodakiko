package dev.kodakiko.contract

import kotlinx.serialization.json.JsonElement

internal object CommonCodecs {
    val id: Codec<String> = StringCodec(pattern = ContractPatterns.ID)
    val bytes: Codec<String> = StringCodec(minLength = 1, pattern = ContractPatterns.BASE64URL, refinement = ::isDecodableBase64Url)
    val isoTime: Codec<String> = StringCodec(pattern = ContractPatterns.ISO_DATETIME)
    val text: Codec<String> = StringCodec()
    val nonEmptyText: Codec<String> = StringCodec(minLength = 1)
    val url: Codec<String> = UrlCodec
    val count: Codec<Long> = IntegerCodec(minimum = 0)
    val positive: Codec<Long> = IntegerCodec(minimum = 1)
    val integer: Codec<Long> = IntegerCodec()
    val boolean: Codec<Boolean> = BooleanCodec
    val unknown: Codec<JsonElement> = UnknownCodec
    val cursor: Codec<String?> = nonEmptyText.optional()
    val queryLimit: Codec<Long?> = IntegerCodec(minimum = 1, coerce = true).optional()
    val userRole: Codec<UserRole> = enumCodec(UserRole.entries)
    val userStatus: Codec<UserStatus> = enumCodec(UserStatus.entries)
    val collectionKind: Codec<CollectionKind> = enumCodec(CollectionKind.entries)
    val collectionRole: Codec<CollectionRole> = enumCodec(CollectionRole.entries)
    val objectVariant: Codec<ObjectVariant> = enumCodec(ObjectVariant.entries)
    val objectState: Codec<ObjectState> = enumCodec(ObjectState.entries)
    val entryPurpose: Codec<EntryPurpose> = enumCodec(EntryPurpose.entries)
    val linkExpiry: Codec<LinkExpiry> = enumCodec(LinkExpiry.entries)

    val idParams: Codec<IdParams> = objectCodec(
        { IdParams(field("id", id)) },
        { field("id", id, it.id) },
    )

    val listQuery: Codec<ListQuery> = objectCodec(
        { ListQuery(field("cursor", cursor), field("limit", queryLimit)) },
        {
            field("cursor", cursor, it.cursor)
            field("limit", queryLimit, it.limit)
        },
    )
}

data class IdParams(val id: String)

data class ListQuery(val cursor: String? = null, val limit: Long? = null)
