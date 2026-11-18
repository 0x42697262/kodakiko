package dev.kodakiko.media

import dev.kodakiko.contract.WireEnum
import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.GoldenReplay
import dev.kodakiko.testing.UnsupportedGoldenFunction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object MediaGoldenAdapter : GoldenAdapter {
    override fun call(fn: String, args: JsonObject): JsonElement = call(fn, args) { it }

    fun call(fn: String, args: JsonObject, wrapSource: (ByteSource) -> ByteSource): JsonElement {
        fun source(): ByteSource = wrapSource(GoldenContent.source(args.getValue("source")))
        return when (fn) {
            "SNIFF_HEADER_BYTES" -> JsonPrimitive(SNIFF_HEADER_BYTES)
            "EXIF_ASCII_MAX_BYTES" -> JsonPrimitive(EXIF_ASCII_MAX_BYTES)
            "KEYS_NAME_MAX_BYTES" -> JsonPrimitive(KEYS_NAME_MAX_BYTES)
            "ISO6709_MAX_BYTES" -> JsonPrimitive(ISO6709_MAX_BYTES)
            "sniffFormat" -> sniffFormat(hex(args.string("header")))?.let(::format) ?: JsonNull
            "parseTiffExif" -> image(parseTiffExif(GoldenReplay.bytes(args.getValue("tiff"))))
            "readImageMetadata" -> image(readImageMetadata(source(), wire(MediaContainer.entries, args.string("container"))))
            "readVideoMetadata" -> video(readVideoMetadata(source()))
            "parseIso6709" -> point(parseIso6709(args.string("text")))
            "resolveCaptureTime" -> time(resolveCaptureTime(captureInput(args.getValue("input").jsonObject)))
            "earliestTime" -> time(earliestTime(args.getValue("times").jsonArray.map { resolvedTime(it.jsonObject) }))
            "deviceOffsetMinutes" -> {
                val offset = args.getValue("timezoneOffsetMinutes").jsonPrimitive.long
                JsonPrimitive(deviceOffsetMinutes { offset })
            }
            "fitWithin" -> size(fitWithin(args.int("width"), args.int("height"), args.int("maxEdge")))
            "displaySize" -> size(displaySize(args.int("width"), args.int("height"), args.getValue("orientation").jsonPrimitive.intOrNull))
            "planIsoBmffStrip" -> plan(planIsoBmffStrip(source()))
            "planWebmDateStrip" -> plan(planWebmDateStrip(source()))
            "streamStripped" -> {
                val segments = args.getValue("plan").jsonObject.getValue("segments").jsonArray.map { segment(it.jsonObject) }
                val output = GoldenContent.output(streamStripped(source(), segments, args.int("chunkBytes")))
                JsonObject(
                    mapOf(
                        "output" to output.reference,
                        "chunkCount" to JsonPrimitive(output.chunkCount),
                        "largestChunk" to JsonPrimitive(output.largestChunk),
                    ),
                )
            }
            "moovHasMetadata" -> JsonPrimitive(moovHasMetadata(hex(args.string("moov"))))
            "hasExifSegment" -> JsonPrimitive(hasExifSegment(GoldenReplay.bytes(args.getValue("bytes"))))
            "byteSourceFromBytes.size" -> JsonPrimitive(byteSourceFromBytes(hex(args.string("bytes"))).size)
            "byteSourceFromBytes.read" ->
                JsonPrimitive(hex(byteSourceFromBytes(hex(args.string("bytes"))).read(args.long("offset"), args.long("length"))))
            else -> throw UnsupportedGoldenFunction(fn)
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.nullableString(key: String): String? = (getValue(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun <E : WireEnum> wire(entries: List<E>, value: String): E = entries.first { it.wire == value }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun number(value: Double): JsonElement = when {
        value.isNaN() -> JsonObject(mapOf("\$number" to JsonPrimitive("NaN")))
        value.isInfinite() -> JsonObject(mapOf("\$number" to JsonPrimitive(if (value > 0) "Infinity" else "-Infinity")))
        value == 0.0 -> JsonPrimitive(0)
        else -> JsonPrimitive(value)
    }

    private fun format(format: MediaFormat): JsonElement = JsonObject(
        mapOf(
            "kind" to JsonPrimitive(format.kind.wire),
            "mimeType" to JsonPrimitive(format.mimeType),
            "container" to JsonPrimitive(format.container.wire),
        ),
    )

    private fun point(point: GeoPoint?): JsonElement =
        point?.let { JsonObject(mapOf("latitude" to number(it.latitude), "longitude" to number(it.longitude))) } ?: JsonNull

    private fun image(metadata: ImageMetadata): JsonElement = JsonObject(
        mapOf(
            "dateTimeOriginal" to JsonPrimitive(metadata.dateTimeOriginal),
            "offsetTimeOriginal" to JsonPrimitive(metadata.offsetTimeOriginal),
            "gps" to point(metadata.gps),
            "orientation" to JsonPrimitive(metadata.orientation),
            "malformed" to JsonPrimitive(metadata.malformed),
        ),
    )

    private fun video(metadata: VideoMetadata): JsonElement = JsonObject(
        mapOf(
            "creationUtc" to JsonPrimitive(metadata.creationUtc),
            "location" to point(metadata.location),
            "rotationDegrees" to JsonPrimitive(metadata.rotationDegrees.degrees),
            "malformed" to JsonPrimitive(metadata.malformed),
        ),
    )

    private fun captureInput(input: JsonObject): CaptureTimeInput = CaptureTimeInput(
        exifDateTimeOriginal = input.nullableString("exifDateTimeOriginal"),
        exifOffset = input.nullableString("exifOffset"),
        containerCreationUtc = input.nullableString("containerCreationUtc"),
        fileModifiedUtc = input.nullableString("fileModifiedUtc"),
        nowUtc = input.string("nowUtc"),
        deviceOffsetMinutes = input.long("deviceOffsetMinutes"),
    )

    private fun resolvedTime(time: JsonObject): ResolvedTime =
        ResolvedTime(time.string("capturedAtUtc"), time.long("utcOffsetMinutes"), wire(TimeSource.entries, time.string("source")))

    private fun time(time: ResolvedTime): JsonElement = JsonObject(
        mapOf(
            "capturedAtUtc" to JsonPrimitive(time.capturedAtUtc),
            "utcOffsetMinutes" to JsonPrimitive(time.utcOffsetMinutes),
            "source" to JsonPrimitive(time.source.wire),
        ),
    )

    private fun size(size: MediaSize): JsonElement =
        JsonObject(mapOf("width" to JsonPrimitive(size.width), "height" to JsonPrimitive(size.height)))

    private fun segment(segment: JsonObject): StripSegment = when (segment.string("kind")) {
        "bytes" -> StripSegment.Bytes(hex(segment.string("bytes")))
        "copy" -> StripSegment.Copy(segment.long("offset"), segment.long("length"))
        else -> error("unknown segment kind in $segment")
    }

    private fun segment(segment: StripSegment): JsonElement = when (segment) {
        is StripSegment.Bytes -> JsonObject(mapOf("kind" to JsonPrimitive("bytes"), "bytes" to JsonPrimitive(hex(segment.bytes))))
        is StripSegment.Copy -> JsonObject(
            mapOf("kind" to JsonPrimitive("copy"), "offset" to JsonPrimitive(segment.offset), "length" to JsonPrimitive(segment.length)),
        )
    }

    private fun plan(plan: StripPlan): JsonElement = JsonObject(
        mapOf(
            "segments" to JsonArray(plan.segments.map(::segment)),
            "outputSize" to JsonPrimitive(plan.outputSize),
            "moov" to JsonPrimitive(hex(plan.moov)),
        ),
    )
}
