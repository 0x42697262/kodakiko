package dev.kodakiko.service.entries

import dev.kodakiko.domain.EntryEdit

suspend fun replaceMedia(
    entries: EntriesService,
    importOne: suspend (MediaInput, ImportSlots) -> ImportOutcome,
    limit: Int,
    entryId: String,
    oldMediaId: String,
    input: MediaInput,
): ReplaceResult {
    val before = entries.readMetadata(entryId)
    val old = before.media.firstOrNull { it.mediaId == oldMediaId } ?: return ReplaceResult.REFUSED
    val outcome = importOne(input, ImportSlots(used = before.media.size - 1, limit = limit))
    if (outcome !is ImportOutcome.Imported) return ReplaceResult.REFUSED
    val newId = outcome.media.mediaId
    entries.addMedia(entryId, listOf(outcome.media))
    entries.removeMedia(entryId, oldMediaId)
    val order = before.media.map { if (it.mediaId == oldMediaId) newId else it.mediaId }
    entries.edit(entryId, EntryEdit(mediaOrder = order, captions = old.caption?.let { mapOf(newId to it) }))
    return ReplaceResult.REPLACED
}
