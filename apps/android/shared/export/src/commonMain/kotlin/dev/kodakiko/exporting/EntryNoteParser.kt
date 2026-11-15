package dev.kodakiko.exporting

import dev.kodakiko.contract.MediaKind
import dev.kodakiko.exporting.ParseConstants.FIELD_CAPTION
import dev.kodakiko.exporting.ParseConstants.FIELD_FILE
import dev.kodakiko.exporting.ParseConstants.FIELD_KIND
import dev.kodakiko.exporting.ParseConstants.FIELD_LATITUDE
import dev.kodakiko.exporting.ParseConstants.FIELD_LONGITUDE
import dev.kodakiko.exporting.ParseConstants.FIELD_ORIGINAL_NAME
import dev.kodakiko.exporting.ParseConstants.FIELD_SOURCE
import dev.kodakiko.js.JsDate
import dev.kodakiko.js.jsSlice
import dev.kodakiko.js.jsStringToNumber
import dev.kodakiko.js.jsTrim

class InvalidEntryNoteError(message: String = ExportText.INVALID_ENTRY_NOTE) : Exception(message)

data class ParsedLocation(val latitude: Double, val longitude: Double, val source: String)

data class ParsedMediaItem(val file: String, val originalName: String?, val kind: MediaKind, val caption: String?)

data class ParsedEntryNote(
    val id: String,
    val takenAtUtc: String,
    val utcOffsetMinutes: Long,
    val tags: List<String>,
    val favorite: Boolean,
    val location: ParsedLocation?,
    val media: List<ParsedMediaItem>,
    val note: String,
)

private sealed interface FlowValue {
    class Scalar(val text: String) : FlowValue

    class Sequence(val items: List<FlowValue>) : FlowValue

    class Mapping(val fields: Map<String, FlowValue>) : FlowValue
}

private fun invalid(): Nothing = throw InvalidEntryNoteError(ExportText.INVALID_ENTRY_NOTE)

private class FlowParser(private val text: String) {
    private var position = 0

    private fun current(): Char? = text.getOrNull(position)

    private fun skip() {
        while (current() == ParseConstants.FLOW_SPACE) position += 1
    }

    private fun next(): Char? = current().also { position += 1 }

    private fun quoted(): String {
        position += 1
        val out = StringBuilder()
        while (position < text.length && text[position] != ParseConstants.FLOW_QUOTE) {
            if (text[position] == ParseConstants.FLOW_ESCAPE) position += 1
            current()?.let(out::append)
            position += 1
        }
        position += 1
        return out.toString()
    }

    private fun scalar(): FlowValue {
        skip()
        if (current() == ParseConstants.FLOW_QUOTE) return FlowValue.Scalar(quoted())
        val start = position
        while (position < text.length && !isScalarEnd(text[position])) position += 1
        return FlowValue.Scalar(jsTrim(text.substring(start, position)))
    }

    private fun isScalarEnd(char: Char): Boolean =
        char == ParseConstants.FLOW_ITEM_SEPARATOR || char == ParseConstants.FLOW_LIST_END || char == ParseConstants.FLOW_MAP_END

    private fun sequence(): FlowValue {
        position += 1
        val items = mutableListOf<FlowValue>()
        skip()
        if (current() == ParseConstants.FLOW_LIST_END) {
            position += 1
            return FlowValue.Sequence(items)
        }
        while (true) {
            items.add(value())
            skip()
            when (next()) {
                ParseConstants.FLOW_LIST_END -> return FlowValue.Sequence(items)
                ParseConstants.FLOW_ITEM_SEPARATOR -> Unit
                else -> invalid()
            }
        }
    }

    private fun mapping(): FlowValue {
        position += 1
        val fields = LinkedHashMap<String, FlowValue>()
        while (true) {
            skip()
            val colon = text.indexOf(ParseConstants.FLOW_KEY_SEPARATOR, position)
            if (colon < 0) invalid()
            val key = jsTrim(text.substring(position, colon))
            position = colon + 1
            fields[key] = value()
            skip()
            when (next()) {
                ParseConstants.FLOW_MAP_END -> return FlowValue.Mapping(fields)
                ParseConstants.FLOW_ITEM_SEPARATOR -> Unit
                else -> invalid()
            }
        }
    }

    fun value(): FlowValue {
        skip()
        return when (current()) {
            ParseConstants.FLOW_LIST_START -> sequence()
            ParseConstants.FLOW_MAP_START -> mapping()
            else -> scalar()
        }
    }
}

private fun parseFlow(text: String): FlowValue = FlowParser(text).value()

private fun text(value: FlowValue?): String = (value as? FlowValue.Scalar)?.text ?: invalid()

private fun headerFields(header: String): Map<String, String> {
    val fields = LinkedHashMap<String, String>()
    for (line in header.split(ParseConstants.LINE_BREAK)) {
        val colon = line.indexOf(ParseConstants.KEY_SEPARATOR)
        if (colon > 0) fields[jsTrim(line.substring(0, colon))] = jsTrim(line.substring(colon + 1))
    }
    return fields
}

private fun location(value: FlowValue?): ParsedLocation? {
    if (value !is FlowValue.Mapping) return null
    val latitude = jsStringToNumber(text(value.fields[FIELD_LATITUDE]))
    val longitude = jsStringToNumber(text(value.fields[FIELD_LONGITUDE]))
    return ParsedLocation(latitude, longitude, text(value.fields[FIELD_SOURCE]))
}

private fun mediaItem(item: FlowValue): ParsedMediaItem {
    if (item !is FlowValue.Mapping) invalid()
    val kindText = text(item.fields[FIELD_KIND])
    val kind = MediaKind.entries.firstOrNull { it.wire == kindText } ?: invalid()
    val caption = item.fields[FIELD_CAPTION]?.let(::text)
    val file = text(item.fields[FIELD_FILE])
    val originalName = item.fields[FIELD_ORIGINAL_NAME]?.let(::text)
    return ParsedMediaItem(file, originalName, kind, caption)
}

fun parseEntryNote(source: String): ParsedEntryNote {
    if (!source.startsWith(ParseConstants.FENCE_LINE)) invalid()
    val end = source.indexOf(ParseConstants.FENCE_BREAK, ParseConstants.FENCE.length)
    if (end < 0) invalid()
    val fields = headerFields(jsSlice(source, ParseConstants.FENCE_LINE.length, end))
    val date = ParseConstants.DATE_PATTERN.matchEntire(fields[ParseConstants.FIELD_DATE] ?: "") ?: invalid()
    val groups = date.groupValues
    val sign = if (groups[ParseConstants.SIGN_GROUP] == ParseConstants.NEGATIVE_SIGN) ParseConstants.SIGN_NEGATIVE else ParseConstants.SIGN_POSITIVE
    val utcOffsetMinutes =
        sign * (groups[ParseConstants.HOURS_GROUP].toLong() * ParseConstants.MINUTES_PER_HOUR + groups[ParseConstants.MINUTES_GROUP].toLong())
    val takenAtUtc = JsDate.toIsoString(
        JsDate.parse(groups[ParseConstants.DATE_GROUP] + ParseConstants.UTC_DESIGNATOR) - utcOffsetMinutes * ParseConstants.MS_PER_MINUTE,
    )
    val tags = parseFlow(fields[ParseConstants.FIELD_TAGS] ?: ParseConstants.EMPTY_LIST)
    val media = parseFlow(fields[ParseConstants.FIELD_MEDIA] ?: ParseConstants.EMPTY_LIST)
    val location = fields[ParseConstants.FIELD_LOCATION]?.let(::parseFlow)
    if (tags !is FlowValue.Sequence || media !is FlowValue.Sequence) invalid()
    val body = source.substring(end + ParseConstants.FENCE_BREAK.length)
    val note = body.removePrefix(ParseConstants.LINE_BREAK).removeSuffix(ParseConstants.LINE_BREAK)
    val id = fields[ParseConstants.FIELD_ID] ?: invalid()
    val tagTexts = tags.items.map(::text)
    val favorite = fields[ParseConstants.FIELD_FAVORITE] == ParseConstants.TRUE
    val parsedLocation = location(location)
    val mediaItems = media.items.map(::mediaItem)
    return ParsedEntryNote(id, takenAtUtc, utcOffsetMinutes, tagTexts, favorite, parsedLocation, mediaItems, note)
}
