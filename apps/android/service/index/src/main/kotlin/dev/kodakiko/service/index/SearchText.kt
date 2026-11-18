package dev.kodakiko.service.index

import dev.kodakiko.contract.EntryMetadataV1

fun searchTextOf(metadata: EntryMetadataV1): String =
    (listOf(metadata.note) + metadata.media.mapNotNull { it.caption }).joinToString(IndexServiceConstants.SEARCH_TEXT_SEPARATOR)
