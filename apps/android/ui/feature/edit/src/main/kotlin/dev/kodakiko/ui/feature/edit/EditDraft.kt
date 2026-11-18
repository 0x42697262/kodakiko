package dev.kodakiko.ui.feature.edit

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.domain.EntryEdit
import dev.kodakiko.domain.EntryTimeEdit

data class EditDraft(
    val mediaIds: List<String>,
    val note: String,
    val tags: List<String>,
    val time: EntryTimeEdit,
    val location: EntryLocation?,
    val captions: Map<String, String>,
)

data class DraftDiff(val removedMediaIds: List<String>, val edit: EntryEdit)

fun draftFromMetadata(current: EntryMetadataV1): EditDraft = EditDraft(
    mediaIds = current.media.map { it.mediaId },
    note = current.note,
    tags = current.tags,
    time = EntryTimeEdit(current.takenAtUtc, current.utcOffsetMinutes),
    location = current.location,
    captions = current.media.mapNotNull { item -> item.caption?.let { item.mediaId to it } }.toMap(),
)

private fun sameTags(left: List<String>, right: List<String>): Boolean = left.sorted() == right.sorted()

private fun sameLocation(left: EntryLocation?, right: EntryLocation?): Boolean {
    if (left == null || right == null) return left == right
    return left.latitude == right.latitude &&
        left.longitude == right.longitude &&
        left.accuracyMeters == right.accuracyMeters &&
        left.source == right.source
}

fun diffDraft(current: EntryMetadataV1, draft: EditDraft): DraftDiff {
    val kept = draft.mediaIds.toSet()
    val currentIds = current.media.map { it.mediaId }
    val removedMediaIds = currentIds.filter { it !in kept }
    val remaining = currentIds.filter { it in kept }
    val time = draft.time.takeIf { it.takenAtUtc != current.takenAtUtc || it.utcOffsetMinutes != current.utcOffsetMinutes }
    val captions = current.media
        .filter { it.mediaId in kept }
        .mapNotNull { item ->
            val wanted = draft.captions[item.mediaId].orEmpty()
            if (wanted != item.caption.orEmpty()) item.mediaId to wanted else null
        }
        .toMap()
    val edit = EntryEdit(
        note = draft.note.takeIf { it != current.note },
        tags = draft.tags.takeIf { !sameTags(it, current.tags) },
        time = time,
        location = if (sameLocation(draft.location, current.location)) OptionalValue.Absent else OptionalValue.Present(draft.location),
        mediaOrder = draft.mediaIds.takeIf { it != remaining },
        captions = captions.takeIf { it.isNotEmpty() },
    )
    return DraftDiff(removedMediaIds, edit)
}

fun draftChanged(diff: DraftDiff): Boolean = diff.removedMediaIds.isNotEmpty() || diff.edit != EntryEdit()
