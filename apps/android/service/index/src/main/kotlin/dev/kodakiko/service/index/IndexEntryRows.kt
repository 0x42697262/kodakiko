package dev.kodakiko.service.index

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.index.MONTH_DAY_START
import dev.kodakiko.index.NOTE_PREVIEW_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlin.math.min

suspend fun indexEntryRows(
    rows: IndexRows,
    userId: String,
    open: MetadataOpener,
    accept: (IndexedEntry, String) -> Unit,
    yieldPoint: suspend () -> Unit = { yield() },
): IndexedRowsCount {
    val membershipsByEntry = rows.memberships.groupBy { it.entryId }
    val mediaByEntry = rows.media.groupBy { it.entryId }
    val objectsByMedia = rows.objects.groupBy { it.mediaId }
    var unreadable = 0
    for ((position, row) in rows.entries.withIndex()) {
        if (position > 0 && position % IndexServiceConstants.INDEX_BUILD_YIELD_EVERY == 0) yieldPoint()
        val collectionIds = membershipsByEntry[row.id].orEmpty().map { it.collectionId }
        val objects = mediaByEntry[row.id].orEmpty().flatMap { objectsByMedia[it.id].orEmpty() }
        val isOwn = row.ownerId == userId
        val pending = isPending(row, objects)
        val metadata = openOrNull(open, row)
        if (metadata == null) {
            unreadable += 1
            accept(placeholderEntry(row, collectionIds, isOwn, pending), "")
        } else {
            accept(readableEntry(row, metadata, collectionIds, isOwn, pending), searchTextOf(metadata))
        }
    }
    return IndexedRowsCount(entries = rows.entries.size, unreadable = unreadable)
}

private suspend fun openOrNull(open: MetadataOpener, row: EntryRow): EntryMetadataV1? =
    try {
        open(row)
    } catch (failure: AccountLockedException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        null
    }

private fun notePreviewOf(note: String): String = note.substring(0, note.offsetByCodePoints(0, min(NOTE_PREVIEW_CHARS, note.codePointCount(0, note.length))))

private fun monthDayOf(localDate: String): String = localDate.drop(MONTH_DAY_START)

private fun isPending(row: EntryRow, objects: List<ObjectRow>): Boolean =
    row.localState != EntryLocalState.SYNCED || objects.any { it.uploadState != ObjectUploadState.UPLOADED }

private fun placeholderEntry(row: EntryRow, collectionIds: List<String>, isOwn: Boolean, pending: Boolean): IndexedEntry {
    val localDate = localDateOf(row.createdAt, 0)
    return IndexedEntry(
        id = row.id,
        isOwn = isOwn,
        collectionIds = collectionIds,
        trashed = row.trashedAt != null,
        unreadable = true,
        takenAtUtc = row.createdAt,
        utcOffsetMinutes = 0,
        localDate = localDate,
        monthDay = monthDayOf(localDate),
        tags = emptyList(),
        coverMediaId = null,
        mediaCount = 0,
        hasVideo = false,
        notePreview = "",
        pending = pending,
    )
}

private fun readableEntry(row: EntryRow, metadata: EntryMetadataV1, collectionIds: List<String>, isOwn: Boolean, pending: Boolean): IndexedEntry {
    val localDate = localDateOf(metadata.takenAtUtc, metadata.utcOffsetMinutes)
    return IndexedEntry(
        id = row.id,
        isOwn = isOwn,
        collectionIds = collectionIds,
        trashed = row.trashedAt != null,
        unreadable = false,
        takenAtUtc = metadata.takenAtUtc,
        utcOffsetMinutes = metadata.utcOffsetMinutes,
        localDate = localDate,
        monthDay = monthDayOf(localDate),
        tags = metadata.tags,
        coverMediaId = metadata.media.firstOrNull()?.mediaId,
        mediaCount = metadata.media.size,
        hasVideo = metadata.media.any { it.kind == MediaKind.VIDEO },
        favorite = metadata.favorite == true,
        videoCount = metadata.media.count { it.kind == MediaKind.VIDEO },
        videoMs = metadata.media.sumOf { it.durationMs ?: 0L },
        originalBytes = metadata.media.sumOf { it.originalPlaintextBytes },
        notePreview = notePreviewOf(metadata.note),
        pending = pending,
    )
}
