package dev.kodakiko.service.index

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.js.JsDate
import kotlin.math.roundToLong

class IndexBenchmarkDeps(
    val sodium: Sodium,
    val randomId: () -> String,
    val now: () -> Long,
    val nowIso: () -> String,
    val count: Int,
)

data class IndexBenchmarkResult(
    val entries: Int,
    val unreadable: Int,
    val buildMs: Long,
    val entriesPerSecond: Long,
    val queryMs: Long,
    val withinBudget: Boolean,
)

private fun unsigned(byte: Byte): Int = byte.toInt() and IndexBenchmarkConstants.BYTE_MASK

private fun randomWord(sodium: Sodium): String {
    val letters = IndexBenchmarkConstants.WORD_LETTERS
    return sodium.randomBytes(IndexBenchmarkConstants.WORD_LENGTH).joinToString("") { letters[unsigned(it) % letters.length].toString() }
}

private fun randomTags(sodium: Sodium): List<String> =
    sodium.randomBytes(IndexBenchmarkConstants.TAGS_PER_ENTRY)
        .map { IndexBenchmarkConstants.TAG_PREFIX + unsigned(it) % IndexBenchmarkConstants.TAGS }
        .distinct()
        .sorted()

private fun benchmarkBinding(entryId: String): String = IndexBenchmarkConstants.COLLECTION + IndexBenchmarkConstants.BINDING_SEPARATOR + entryId

private fun syntheticMetadata(deps: IndexBenchmarkDeps, position: Int): EntryMetadataV1 {
    val now = deps.now()
    val takenAtUtc = JsDate.toIsoString((now - position * IndexBenchmarkConstants.SPACING_MINUTES * IndexBenchmarkConstants.MS_PER_MINUTE).toDouble())
    val words = List(IndexBenchmarkConstants.WORDS_PER_NOTE) { randomWord(deps.sodium) }
    return EntryMetadataV1(
        takenAtUtc = takenAtUtc,
        utcOffsetMinutes = 0,
        timeZone = null,
        note = words.joinToString(IndexBenchmarkConstants.WORD_SEPARATOR),
        tags = randomTags(deps.sodium),
        location = null,
        media = listOf(
            MediaDescriptorV1(
                mediaId = deps.randomId(),
                kind = MediaKind.PHOTO,
                mimeType = IndexBenchmarkConstants.MIME,
                width = IndexBenchmarkConstants.SIDE,
                height = IndexBenchmarkConstants.SIDE,
                durationMs = null,
                originalPlaintextBytes = IndexBenchmarkConstants.BYTES,
                thumbnail = ThumbnailDescriptor(
                    width = IndexBenchmarkConstants.SIDE,
                    height = IndexBenchmarkConstants.SIDE,
                    plaintextBytes = IndexBenchmarkConstants.BYTES,
                ),
                capturedAtUtc = null,
                originalFileName = null,
            ),
        ),
        createdAtUtc = takenAtUtc,
        editedAtUtc = takenAtUtc,
    )
}

suspend fun runIndexBenchmark(deps: IndexBenchmarkDeps): IndexBenchmarkResult {
    val sodium = deps.sodium
    val collectionKey = sodium.randomBytes(KEY_BYTES)
    val entries = mutableListOf<EntryRow>()
    val memberships = mutableListOf<MembershipRow>()
    val nowIso = deps.nowIso()
    for (position in 0 until deps.count) {
        val entryId = deps.randomId()
        val entryKey = sodium.randomBytes(KEY_BYTES)
        entries.add(
            EntryRow(
                id = entryId,
                ownerId = IndexBenchmarkConstants.OWNER,
                purpose = EntryPurpose.JOURNAL,
                revision = IndexBenchmarkConstants.REVISION,
                metadataBlob = sealPayload(sodium, entryKey, syntheticMetadata(deps, position), ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId),
                baseMetadataBlob = null,
                baseRevision = IndexBenchmarkConstants.REVISION,
                serverPendingBlob = null,
                serverPendingRevision = null,
                trashedAt = null,
                createdAt = nowIso,
                updatedAt = nowIso,
                localState = EntryLocalState.SYNCED,
            ),
        )
        memberships.add(
            MembershipRow(
                collectionId = IndexBenchmarkConstants.COLLECTION,
                entryId = entryId,
                entryKeyWrap = wrapKey(sodium, collectionKey, entryKey, AdContext.ENTRY_KEY_BY_COLLECTION, benchmarkBinding(entryId)),
                seq = IndexBenchmarkConstants.MEMBERSHIP_SEQ,
            ),
        )
        wipe(entryKey)
    }
    val wraps = memberships.associate { it.entryId to it.entryKeyWrap }
    val index = GalleryIndex(IndexBenchmarkConstants.COLLECTION)
    val started = deps.now()
    val counts = indexEntryRows(
        IndexRows(entries, memberships, emptyList(), emptyList()),
        IndexBenchmarkConstants.OWNER,
        { row ->
            val entryKey = unwrapKey(sodium, collectionKey, wraps[row.id] ?: ByteArray(0), AdContext.ENTRY_KEY_BY_COLLECTION, benchmarkBinding(row.id))
            try {
                openEntryMetadata(sodium, entryKey, row.id, row.metadataBlob)
            } finally {
                wipe(entryKey)
            }
        },
        index::upsert,
    )
    val buildMs = deps.now() - started
    val queryStarted = deps.now()
    index.query(
        GalleryFilter(
            scope = GalleryScope.Home,
            view = GalleryView.JOURNAL,
            tags = listOf(IndexBenchmarkConstants.TAG_PREFIX + 0),
            fromDate = null,
            toDate = null,
            text = IndexBenchmarkConstants.QUERY_PREFIX,
        ),
    )
    val queryMs = deps.now() - queryStarted
    wipe(collectionKey)
    return IndexBenchmarkResult(
        entries = deps.count,
        unreadable = counts.unreadable,
        buildMs = buildMs,
        entriesPerSecond = if (buildMs == 0L) deps.count.toLong() else (deps.count * IndexBenchmarkConstants.MS_PER_SECOND / buildMs).roundToLong(),
        queryMs = queryMs,
        withinBudget = buildMs <= IndexServiceConstants.UNLOCK_BUDGET_MS,
    )
}
