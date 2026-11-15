package dev.kodakiko.contract

enum class OutboxKind(override val wire: String) : WireEnum {
    CREATE_ENTRY("createEntry"),
    UPDATE_ENTRY_META("updateEntryMeta"),
    ADD_MEDIA("addMedia"),
    REMOVE_MEDIA("removeMedia"),
    TRASH_ENTRY("trashEntry"),
    RESTORE_ENTRY("restoreEntry"),
    DELETE_ENTRY("deleteEntry"),
}

data class EntryOutboxPayload(val entryId: String)

data class EntryMediaOutboxPayload(val entryId: String, val mediaId: String)

object OutboxPayloadSchemas {
    val entry: ContractSchema<EntryOutboxPayload> = schema(
        "entryOutboxPayload",
        objectCodec(
            { EntryOutboxPayload(field("entryId", CommonCodecs.id)) },
            { field("entryId", CommonCodecs.id, it.entryId) },
        ),
    )

    val entryMedia: ContractSchema<EntryMediaOutboxPayload> = schema(
        "entryMediaOutboxPayload",
        objectCodec(
            { EntryMediaOutboxPayload(field("entryId", CommonCodecs.id), field("mediaId", CommonCodecs.id)) },
            {
                field("entryId", CommonCodecs.id, it.entryId)
                field("mediaId", CommonCodecs.id, it.mediaId)
            },
        ),
    )
}

val entryOutboxPayloadSchemas: Map<OutboxKind, ContractSchema<*>> = mapOf(
    OutboxKind.CREATE_ENTRY to OutboxPayloadSchemas.entry,
    OutboxKind.UPDATE_ENTRY_META to OutboxPayloadSchemas.entry,
    OutboxKind.ADD_MEDIA to OutboxPayloadSchemas.entryMedia,
    OutboxKind.REMOVE_MEDIA to OutboxPayloadSchemas.entryMedia,
    OutboxKind.TRASH_ENTRY to OutboxPayloadSchemas.entry,
    OutboxKind.RESTORE_ENTRY to OutboxPayloadSchemas.entry,
    OutboxKind.DELETE_ENTRY to OutboxPayloadSchemas.entry,
)
