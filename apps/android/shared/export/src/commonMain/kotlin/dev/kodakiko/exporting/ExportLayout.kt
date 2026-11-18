package dev.kodakiko.exporting

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.exporting.LayoutConstants.COORDINATE_DECIMALS
import dev.kodakiko.exporting.LayoutConstants.DATE_CHARS
import dev.kodakiko.exporting.LayoutConstants.FENCE
import dev.kodakiko.exporting.LayoutConstants.LINE_BREAK
import dev.kodakiko.exporting.LayoutConstants.LIST_SEPARATOR
import dev.kodakiko.exporting.LayoutConstants.TIME_END
import dev.kodakiko.exporting.LayoutConstants.TIME_START
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.isJsWhitespace
import dev.kodakiko.js.jsSlice
import dev.kodakiko.js.jsToFixed
import kotlin.math.abs

data class ExportMediaPlan(val mediaId: String, val fileName: String, val plaintextBytes: Long)

data class ExportEntryPlan(
    val folder: String,
    val noteFile: String,
    val noteText: String,
    val media: List<ExportMediaPlan>,
    val mtime: Double,
)

data class PlannedFile(val entryId: String, val path: String, val bytes: Long)

private fun pad(value: Long, digits: Int): String = value.toString().padStart(digits, LayoutConstants.PAD)

private fun localIso(metadata: EntryMetadataV1): String =
    JsDate.toIsoString(JsDate.parse(metadata.takenAtUtc) + metadata.utcOffsetMinutes * LayoutConstants.MS_PER_MINUTE)

private fun offsetText(minutes: Long): String {
    val sign = if (minutes < 0) LayoutConstants.MINUS else LayoutConstants.PLUS
    val absolute = abs(minutes)
    val hours = pad(absolute / LayoutConstants.MINUTES_PER_HOUR, LayoutConstants.TWO_DIGITS)
    return sign + hours + LayoutConstants.TIME_SEPARATOR + pad(absolute % LayoutConstants.MINUTES_PER_HOUR, LayoutConstants.TWO_DIGITS)
}

private fun mediaFileName(position: Int, mimeType: String): String =
    pad(position + 1L, LayoutConstants.POSITION_DIGITS) + LayoutConstants.EXTENSION_SEPARATOR +
        (EXPORT_EXTENSIONS[mimeType] ?: EXPORT_FALLBACK_EXTENSION)

private fun needsQuotes(value: String): Boolean =
    value.any { it in LayoutConstants.YAML_NEEDS_QUOTES } || isJsWhitespace(value.first()) || isJsWhitespace(value.last())

fun yamlQuote(value: String): String {
    if (value.isNotEmpty() && !needsQuotes(value)) return value
    val escaped = value.replace(LayoutConstants.YAML_BACKSLASH, LayoutConstants.YAML_ESCAPED_BACKSLASH)
        .replace(LayoutConstants.YAML_QUOTE, LayoutConstants.YAML_ESCAPED_QUOTE)
    return LayoutConstants.YAML_QUOTE + escaped + LayoutConstants.YAML_QUOTE
}

private fun flowMap(parts: List<String>): String = "{ ${parts.joinToString(LIST_SEPARATOR)} }"

private fun flowList(items: List<String>): String = "[${items.joinToString(LIST_SEPARATOR)}]"

fun frontMatter(entryId: String, metadata: EntryMetadataV1): String {
    val local = localIso(metadata)
    val date = jsSlice(local, 0, DATE_CHARS) + LayoutConstants.DATE_TIME_SEPARATOR + jsSlice(local, TIME_START, TIME_END) +
        offsetText(metadata.utcOffsetMinutes)
    val lines = mutableListOf(
        FENCE,
        "id: $entryId",
        "date: $date",
        "tags: ${flowList(metadata.tags.map(::yamlQuote))}",
    )
    if (metadata.favorite == true) lines.add(LayoutConstants.FAVORITE_LINE)
    metadata.location?.let { location ->
        val latitude = jsToFixed(location.latitude, COORDINATE_DECIMALS)
        val longitude = jsToFixed(location.longitude, COORDINATE_DECIMALS)
        lines.add("location: ${flowMap(listOf("latitude: $latitude", "longitude: $longitude", "source: ${location.source.wire}"))}")
    }
    val media = metadata.media.mapIndexed { position, item ->
        val parts = mutableListOf("file: ${mediaFileName(position, item.mimeType)}")
        item.originalFileName?.let { parts.add("original_name: ${yamlQuote(it)}") }
        parts.add("kind: ${item.kind.wire}")
        item.caption?.let { parts.add("caption: ${yamlQuote(it)}") }
        flowMap(parts)
    }
    lines.add("media: ${flowList(media)}")
    lines.add(FENCE)
    lines.add("")
    return lines.joinToString(LINE_BREAK)
}

fun planEntryExport(entryId: String, metadata: EntryMetadataV1): ExportEntryPlan {
    val local = localIso(metadata)
    val dateParts = jsSlice(local, 0, DATE_CHARS).split(LayoutConstants.DATE_SEPARATOR)
    val year = dateParts.getOrElse(LayoutConstants.YEAR_PART) { "" }
    val month = dateParts.getOrElse(LayoutConstants.MONTH_PART) { "" }
    val day = dateParts.getOrElse(LayoutConstants.DAY_PART) { "" }
    val time = jsSlice(local, TIME_START, TIME_START + LayoutConstants.HOUR_MINUTE_CHARS).replaceFirst(LayoutConstants.TIME_SEPARATOR, "")
    val separator = LayoutConstants.PATH_SEPARATOR
    val dash = LayoutConstants.DATE_SEPARATOR
    val underscore = LayoutConstants.FOLDER_PART_SEPARATOR
    val folder = year + separator + month + separator + year + dash + month + dash + day + underscore + time + underscore +
        entryId.take(LayoutConstants.ID_PREFIX_CHARS) + separator
    return ExportEntryPlan(
        folder = folder,
        noteFile = folder + EXPORT_NOTE_FILE,
        noteText = frontMatter(entryId, metadata) + LINE_BREAK + metadata.note + LINE_BREAK,
        media = metadata.media.mapIndexed { position, item ->
            ExportMediaPlan(item.mediaId, mediaFileName(position, item.mimeType), item.originalPlaintextBytes)
        },
        mtime = JsDate.parse(metadata.takenAtUtc),
    )
}

fun planParts(files: List<PlannedFile>, partMaxBytes: Long, partMaxFiles: Long): List<List<PlannedFile>> {
    val parts = mutableListOf<List<PlannedFile>>()
    var current = mutableListOf<PlannedFile>()
    var bytes = 0L
    for (file in files) {
        val fits = bytes + file.bytes + EXPORT_FILE_OVERHEAD_BYTES <= partMaxBytes && current.size < partMaxFiles
        if (!fits && current.isNotEmpty()) {
            parts.add(current)
            current = mutableListOf()
            bytes = 0L
        }
        current.add(file)
        bytes += file.bytes + EXPORT_FILE_OVERHEAD_BYTES
    }
    if (current.isNotEmpty()) parts.add(current)
    return parts
}

fun partFileName(exportDate: String, partNumber: Long): String = "kodakiko-export-$exportDate-part${pad(partNumber, LayoutConstants.PART_DIGITS)}.zip"

fun clampZipDate(time: Double): Double = when {
    time < EXPORT_MTIME_MIN -> EXPORT_MTIME_MIN
    time > EXPORT_MTIME_MAX -> EXPORT_MTIME_MAX
    else -> time
}
