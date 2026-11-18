package dev.kodakiko.sync

import dev.kodakiko.contract.EntryLocation
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.domain.normalizeTag
import dev.kodakiko.js.JsDate

private enum class Side { LOCAL, SERVER }

private fun sameNumber(left: Double?, right: Double?): Boolean =
    if (left == null || right == null) left == null && right == null else left == right

private fun sameLocation(left: EntryLocation?, right: EntryLocation?): Boolean {
    if (left == null || right == null) return left == null && right == null
    return sameNumber(left.latitude, right.latitude) &&
        sameNumber(left.longitude, right.longitude) &&
        sameNumber(left.accuracyMeters, right.accuracyMeters) &&
        left.source == right.source
}

private fun sameMedia(left: List<MediaDescriptorV1>, right: List<MediaDescriptorV1>): Boolean = left == right

private fun sameTimeUnit(left: EntryMetadataV1, right: EntryMetadataV1): Boolean =
    left.takenAtUtc == right.takenAtUtc && left.utcOffsetMinutes == right.utcOffsetMinutes && left.timeZone == right.timeZone

private fun normalizedTags(tags: List<String>): List<String> = tags.mapNotNull(::normalizeTag)

private fun isFavorite(metadata: EntryMetadataV1): Boolean = metadata.favorite == true

private fun laterEditWins(local: EntryMetadataV1, server: EntryMetadataV1): Side =
    if (JsDate.parse(server.editedAtUtc) > JsDate.parse(local.editedAtUtc)) Side.SERVER else Side.LOCAL

private fun <Value> pickSide(
    changedLocally: Boolean,
    changedOnServer: Boolean,
    local: EntryMetadataV1,
    server: EntryMetadataV1,
    localValue: Value,
    serverValue: Value,
): Value = when {
    changedLocally && !changedOnServer -> localValue
    !changedLocally && changedOnServer -> serverValue
    !changedLocally -> localValue
    laterEditWins(local, server) == Side.SERVER -> serverValue
    else -> localValue
}

private fun mergeTags(ancestor: EntryMetadataV1, local: EntryMetadataV1, server: EntryMetadataV1): List<String> {
    val base = normalizedTags(ancestor.tags).toSet()
    val localTags = normalizedTags(local.tags).toSet()
    val serverTags = normalizedTags(server.tags).toSet()
    val merged = LinkedHashSet<String>()
    for (tag in base) {
        if (tag in localTags && tag in serverTags) merged.add(tag)
    }
    for (tag in localTags + serverTags) {
        if (tag !in base) merged.add(tag)
    }
    return merged.sorted()
}

private fun withMergedCaption(server: MediaDescriptorV1, ancestor: MediaDescriptorV1?, local: MediaDescriptorV1): MediaDescriptorV1 {
    val localChanged = local.caption != ancestor?.caption
    if (!localChanged || local.caption == server.caption) return server
    return server.copy(caption = local.caption)
}

private fun mergeMedia(ancestor: EntryMetadataV1, local: EntryMetadataV1, server: EntryMetadataV1): List<MediaDescriptorV1> {
    val ancestorById = ancestor.media.associateBy { it.mediaId }
    val localById = local.media.associateBy { it.mediaId }
    val serverById = server.media.associateBy { it.mediaId }
    val merged = mutableListOf<MediaDescriptorV1>()
    for (item in local.media) {
        val removedOnServer = item.mediaId in ancestorById && item.mediaId !in serverById
        if (removedOnServer) continue
        val onServer = serverById[item.mediaId]
        merged.add(if (onServer == null) item else withMergedCaption(onServer, ancestorById[item.mediaId], item))
    }
    for (item in server.media) {
        val knownLocally = item.mediaId in localById
        val removedLocally = item.mediaId in ancestorById && !knownLocally
        if (!knownLocally && !removedLocally) merged.add(item)
    }
    return merged
}

fun mergeMetadata(ancestor: EntryMetadataV1, local: EntryMetadataV1, server: EntryMetadataV1, nowUtc: String): EntryMetadataV1 {
    val timeSide = pickSide(!sameTimeUnit(ancestor, local), !sameTimeUnit(ancestor, server), local, server, local, server)
    val location = pickSide(
        !sameLocation(ancestor.location, local.location),
        !sameLocation(ancestor.location, server.location),
        local,
        server,
        local.location,
        server.location,
    )
    val note = pickSide(ancestor.note != local.note, ancestor.note != server.note, local, server, local.note, server.note)
    val favorite = pickSide(
        isFavorite(ancestor) != isFavorite(local),
        isFavorite(ancestor) != isFavorite(server),
        local,
        server,
        isFavorite(local),
        isFavorite(server),
    )
    return EntryMetadataV1(
        takenAtUtc = timeSide.takenAtUtc,
        utcOffsetMinutes = timeSide.utcOffsetMinutes,
        timeZone = timeSide.timeZone,
        note = note,
        tags = mergeTags(ancestor, local, server),
        location = location,
        media = mergeMedia(ancestor, local, server),
        createdAtUtc = ancestor.createdAtUtc,
        editedAtUtc = nowUtc,
        favorite = if (favorite) true else null,
    )
}

fun metadataEquivalent(left: EntryMetadataV1, right: EntryMetadataV1): Boolean =
    left.takenAtUtc == right.takenAtUtc &&
        left.utcOffsetMinutes == right.utcOffsetMinutes &&
        left.timeZone == right.timeZone &&
        left.note == right.note &&
        left.createdAtUtc == right.createdAtUtc &&
        normalizedTags(left.tags) == normalizedTags(right.tags) &&
        sameLocation(left.location, right.location) &&
        sameMedia(left.media, right.media) &&
        isFavorite(left) == isFavorite(right)
