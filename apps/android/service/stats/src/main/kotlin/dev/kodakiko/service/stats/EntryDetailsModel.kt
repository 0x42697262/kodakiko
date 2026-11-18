package dev.kodakiko.service.stats

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.domain.localDateOf
import dev.kodakiko.domain.localTimeOf
import dev.kodakiko.js.jsToFixed
import kotlin.math.abs

enum class DetailSectionKind {
    ENTRY,
    PHOTO,
    VIDEO,
}

enum class DetailLabel {
    ID,
    TAKEN,
    CREATED,
    EDITED,
    VERSION,
    SYNC,
    LOCATION,
    TAGS,
    NOTE_LENGTH,
    FILE_NAME,
    TYPE,
    SIZE,
    DIMENSIONS,
    DURATION,
    CAPTURED_AT,
    ORIGINAL,
    THUMBNAIL,
    MEDIA_ID,
    ENCRYPTED_SIZE,
}

sealed interface DetailValue {
    data class Text(val text: String) : DetailValue

    data class Characters(val count: Int) : DetailValue

    data class Bytes(val bytes: Long) : DetailValue

    data class Duration(val ms: Long) : DetailValue

    data class Storage(val onDevice: Boolean, val uploaded: Boolean) : DetailValue

    data object Unknown : DetailValue
}

data class DetailRow(val label: DetailLabel, val value: DetailValue)

data class DetailSection(val kind: DetailSectionKind, val position: Int?, val rows: List<DetailRow>)

private fun offsetText(minutes: Long): String {
    val sign = if (minutes < 0) DetailKeys.MINUS else DetailKeys.PLUS
    val absolute = abs(minutes)
    val hours = (absolute / StatsConstants.MINUTES_PER_HOUR).toString().padStart(StatsConstants.PAD, StatsConstants.PAD_CHAR)
    val rest = (absolute % StatsConstants.MINUTES_PER_HOUR).toString().padStart(StatsConstants.PAD, StatsConstants.PAD_CHAR)
    return DetailKeys.UTC + sign + hours + DetailKeys.TIME_SEPARATOR + rest
}

private fun utcText(iso: String): String =
    iso.take(StatsConstants.DATE_TIME_CHARS).replaceFirst(DetailKeys.ISO_TIME_MARKER, DetailKeys.SPACE) + DetailKeys.UTC_SUFFIX

private fun stateOf(stored: ObjectRow?): DetailValue =
    if (stored == null) {
        DetailValue.Unknown
    } else {
        DetailValue.Storage(onDevice = stored.localState == ObjectLocalState.PRESENT, uploaded = stored.uploadState == ObjectUploadState.UPLOADED)
    }

private fun text(label: DetailLabel, value: String): DetailRow = DetailRow(label, DetailValue.Text(value))

private fun entryRows(entryId: String, metadata: EntryMetadataV1, row: EntryRow?, technical: Boolean): List<DetailRow> {
    val local = localDateOf(metadata.takenAtUtc, metadata.utcOffsetMinutes) + DetailKeys.WORD_SEPARATOR +
        localTimeOf(metadata.takenAtUtc, metadata.utcOffsetMinutes)
    val offset = offsetText(metadata.utcOffsetMinutes)
    val zone = metadata.timeZone?.let { offset + DetailKeys.LIST_SEPARATOR + it } ?: offset
    val rows = mutableListOf<DetailRow>()
    if (technical) rows += text(DetailLabel.ID, entryId)
    rows += text(DetailLabel.TAKEN, local + DetailKeys.OPEN + zone + DetailKeys.CLOSE)
    if (row != null) {
        rows += text(DetailLabel.CREATED, utcText(row.createdAt))
        rows += text(DetailLabel.EDITED, utcText(row.updatedAt))
        rows += text(DetailLabel.VERSION, row.revision.toString())
        rows += text(DetailLabel.SYNC, row.localState.wire)
    }
    metadata.location?.let { location ->
        val latitude = jsToFixed(location.latitude, StatsConstants.COORDINATE_DECIMALS)
        val longitude = jsToFixed(location.longitude, StatsConstants.COORDINATE_DECIMALS)
        rows += text(DetailLabel.LOCATION, latitude + DetailKeys.LIST_SEPARATOR + longitude + DetailKeys.OPEN + location.source.wire + DetailKeys.CLOSE)
    }
    if (metadata.tags.isNotEmpty()) rows += text(DetailLabel.TAGS, metadata.tags.joinToString(DetailKeys.WORD_SEPARATOR) { DetailKeys.TAG_PREFIX + it })
    rows += DetailRow(DetailLabel.NOTE_LENGTH, DetailValue.Characters(metadata.note.length))
    return rows
}

fun entryDetailSections(
    entryId: String,
    metadata: EntryMetadataV1,
    row: EntryRow?,
    objects: Map<String, List<ObjectRow>>,
    technical: Boolean,
): List<DetailSection> {
    val mediaSections = metadata.media.mapIndexed { index, item ->
        val stored = objects[item.mediaId].orEmpty()
        val original = stored.firstOrNull { it.variant == ObjectVariant.ORIGINAL }
        val thumbnail = stored.firstOrNull { it.variant != ObjectVariant.ORIGINAL }
        val rows = mutableListOf<DetailRow>()
        item.originalFileName?.let { rows += text(DetailLabel.FILE_NAME, it) }
        rows += text(DetailLabel.TYPE, item.mimeType)
        rows += DetailRow(DetailLabel.SIZE, DetailValue.Bytes(item.originalPlaintextBytes))
        rows += text(DetailLabel.DIMENSIONS, item.width.toString() + DetailKeys.DIMENSION_SEPARATOR + item.height.toString())
        item.durationMs?.let { rows += DetailRow(DetailLabel.DURATION, DetailValue.Duration(it)) }
        item.capturedAtUtc?.let { rows += text(DetailLabel.CAPTURED_AT, utcText(it)) }
        rows += DetailRow(DetailLabel.ORIGINAL, stateOf(original))
        if (thumbnail != null) rows += DetailRow(DetailLabel.THUMBNAIL, stateOf(thumbnail))
        if (technical) {
            rows += text(DetailLabel.MEDIA_ID, item.mediaId)
            if (original != null) rows += DetailRow(DetailLabel.ENCRYPTED_SIZE, DetailValue.Bytes(original.sizeBytes))
        }
        val kind = if (item.kind == MediaKind.VIDEO) DetailSectionKind.VIDEO else DetailSectionKind.PHOTO
        DetailSection(kind, index + StatsConstants.FIRST_POSITION, rows)
    }
    return listOf(DetailSection(DetailSectionKind.ENTRY, null, entryRows(entryId, metadata, row, technical))) + mediaSections
}
