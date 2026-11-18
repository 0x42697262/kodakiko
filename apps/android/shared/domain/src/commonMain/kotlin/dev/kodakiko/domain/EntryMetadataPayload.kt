package dev.kodakiko.domain

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ENTRY_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.openPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.Sodium

fun openEntryMetadata(sodium: Sodium, entryKey: ByteArray, entryId: String, blob: ByteArray): EntryMetadataV1 =
    openPayload(
        sodium,
        entryKey,
        blob,
        ContractSchemas.entryMetadataV1,
        ENTRY_METADATA_SCHEMA_VERSION,
        emptyMap(),
        AdContext.ENTRY_METADATA,
        entryId,
    )
