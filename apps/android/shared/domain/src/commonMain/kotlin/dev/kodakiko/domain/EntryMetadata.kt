package dev.kodakiko.domain

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.LocationSource
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsTrim
import dev.kodakiko.media.GeoPoint
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.earliestTime

data class DeviceLocation(val point: GeoPoint, val accuracyMeters: Double?)

data class EntryDraft(
    val media: List<MediaDescriptorV1>,
    val times: List<ResolvedTime>,
    val note: String,
    val tags: List<String>,
    val mediaLocations: List<GeoPoint?>,
    val deviceLocation: DeviceLocation?,
    val timeZone: String?,
    val nowUtc: String,
)

data class EntryTimeEdit(val takenAtUtc: String, val utcOffsetMinutes: Long)

data class EntryEdit(
    val note: String? = null,
    val tags: List<String>? = null,
    val time: EntryTimeEdit? = null,
    val location: OptionalValue<EntryLocation?> = OptionalValue.Absent,
    val mediaOrder: List<String>? = null,
    val addMedia: List<MediaDescriptorV1>? = null,
    val removeMediaId: String? = null,
    val favorite: Boolean? = null,
    val captions: Map<String, String>? = null,
)

private fun parseEntryMetadata(metadata: EntryMetadataV1): EntryMetadataV1 =
    ContractSchemas.entryMetadataV1.decode(ContractSchemas.entryMetadataV1.encode(metadata))

private fun cleanTags(tags: List<String>): List<String> {
    val cleaned = LinkedHashSet<String>()
    for (tag in tags) cleaned.add(normalizeTag(tag) ?: throw InvalidEditError(DomainText.INVALID_TAG))
    return sortTags(cleaned.toList())
}

private fun earliestLocatedPoint(draft: EntryDraft): GeoPoint? {
    var bestPoint: GeoPoint? = null
    var bestAt = Double.POSITIVE_INFINITY
    draft.mediaLocations.forEachIndexed { position, point ->
        val time = draft.times.getOrNull(position)
        if (point == null || time == null) return@forEachIndexed
        val at = JsDate.parse(time.capturedAtUtc)
        if (at < bestAt) {
            bestPoint = point
            bestAt = at
        }
    }
    return bestPoint
}

private fun draftLocation(draft: EntryDraft): EntryLocation? {
    val point = earliestLocatedPoint(draft)
    if (point != null) return EntryLocation(point.latitude, point.longitude, null, LocationSource.EXIF)
    val device = draft.deviceLocation ?: return null
    return EntryLocation(device.point.latitude, device.point.longitude, device.accuracyMeters, LocationSource.DEVICE)
}

fun buildNewEntryMetadata(draft: EntryDraft): EntryMetadataV1 {
    val taken = earliestTime(draft.times)
    return parseEntryMetadata(
        EntryMetadataV1(
            takenAtUtc = taken.capturedAtUtc,
            utcOffsetMinutes = taken.utcOffsetMinutes,
            timeZone = draft.timeZone,
            note = draft.note,
            tags = cleanTags(draft.tags),
            location = draftLocation(draft),
            media = draft.media,
            createdAtUtc = draft.nowUtc,
            editedAtUtc = draft.nowUtc,
        ),
    )
}

private fun reordered(media: List<MediaDescriptorV1>, order: List<String>): List<MediaDescriptorV1> {
    val byId = media.associateBy { it.mediaId }
    val isPermutation = order.size == media.size && order.toSet().size == order.size && order.all { it in byId }
    if (!isPermutation) throw InvalidEditError(DomainText.MEDIA_ORDER_NOT_PERMUTATION)
    return order.map { byId.getValue(it) }
}

private fun withoutMedia(media: List<MediaDescriptorV1>, mediaId: String): List<MediaDescriptorV1> {
    if (media.none { it.mediaId == mediaId }) throw InvalidEditError(DomainText.UNKNOWN_MEDIA)
    return media.filter { it.mediaId != mediaId }
}

private fun withCaptions(media: List<MediaDescriptorV1>, captions: Map<String, String>): List<MediaDescriptorV1> =
    media.map { item ->
        val next = captions[item.mediaId] ?: return@map item
        val trimmed = jsTrim(next)
        item.copy(caption = trimmed.ifEmpty { null })
    }

fun applyEntryEdit(current: EntryMetadataV1, edit: EntryEdit, nowUtc: String): EntryMetadataV1 {
    var media = current.media
    edit.mediaOrder?.let { media = reordered(media, it) }
    edit.removeMediaId?.let { media = withoutMedia(media, it) }
    edit.addMedia?.let { media = media + it }
    edit.captions?.let { media = withCaptions(media, it) }
    if (media.isEmpty()) throw InvalidEditError(DomainText.REMOVE_LAST_MEDIA)
    val favorite = edit.favorite ?: (current.favorite == true)
    val location = when (val edited = edit.location) {
        OptionalValue.Absent -> current.location
        is OptionalValue.Present -> edited.value
    }
    return parseEntryMetadata(
        current.copy(
            favorite = if (favorite) true else null,
            note = edit.note ?: current.note,
            tags = edit.tags?.let(::cleanTags) ?: current.tags,
            takenAtUtc = edit.time?.takenAtUtc ?: current.takenAtUtc,
            utcOffsetMinutes = edit.time?.utcOffsetMinutes ?: current.utcOffsetMinutes,
            location = location,
            media = media,
            editedAtUtc = nowUtc,
        ),
    )
}
