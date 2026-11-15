package dev.kodakiko.media

import dev.kodakiko.testing.GoldenReplay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.security.MessageDigest

class SourceFailure(message: String) : Exception(message)

object GoldenContent {
    private const val FILL_MIN_RUN = 4096
    private const val HASH_BLOCK_BYTES = 1_048_576
    private const val OUTPUTS_DIRECTORY = "media/outputs"
    private val verified = mutableSetOf<String>()
    private val outputsBySha: Map<String, List<String>> by lazy {
        File(GoldenReplay.goldenDirectory, OUTPUTS_DIRECTORY).listFiles().orEmpty().filter { it.isFile }
            .groupBy({ GoldenReplay.sha256(it.readBytes()) }, { "$OUTPUTS_DIRECTORY/${it.name}" })
    }

    private sealed interface Part {
        val size: Long
    }

    private class Stored(val bytes: ByteArray) : Part {
        override val size: Long get() = bytes.size.toLong()
    }

    private class Fill(val value: Int, override val size: Long) : Part

    fun source(reference: JsonElement): ByteSource {
        val obj = reference.jsonObject
        obj["failing"]?.let { failing ->
            val size = failing.jsonObject.getValue("size").jsonPrimitive.long
            val message = failing.jsonObject.getValue("message").jsonPrimitive.content
            return object : ByteSource {
                override val size: Long = size

                override fun read(offset: Long, length: Long): ByteArray = throw SourceFailure(message)
            }
        }
        if (obj["file"] != null) return byteSourceFromBytes(GoldenReplay.bytes(reference))
        val parts = obj.getValue("segments").jsonArray.map { segment ->
            val fields = segment.jsonObject
            val size = fields.getValue("size").jsonPrimitive.long
            fields["file"]?.let { Stored(File(GoldenReplay.goldenDirectory, it.jsonPrimitive.content).readBytes()) }
                ?: Fill(fields.getValue("fill").jsonPrimitive.content.toInt(), size)
        }
        val size = obj.getValue("size").jsonPrimitive.long
        check(parts.sumOf { it.size } == size) { "segments of $reference do not add up to $size bytes" }
        val sha256 = obj.getValue("sha256").jsonPrimitive.content
        if (sha256 !in verified) {
            check(digest(parts) == sha256) { "segmented reference has the wrong sha256 $sha256" }
            verified += sha256
        }
        return SegmentedSource(parts, size)
    }

    private fun digest(parts: List<Part>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            when (part) {
                is Stored -> digest.update(part.bytes)
                is Fill -> {
                    val block = ByteArray(minOf(HASH_BLOCK_BYTES.toLong(), part.size).toInt()) { part.value.toByte() }
                    var done = 0L
                    while (done < part.size) {
                        val length = minOf(block.size.toLong(), part.size - done).toInt()
                        digest.update(block, 0, length)
                        done += length
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class SegmentedSource(private val parts: List<Part>, override val size: Long) : ByteSource {
        override fun read(offset: Long, length: Long): ByteArray {
            val start = maxOf(0L, offset)
            val end = minOf(size, start + maxOf(0L, length))
            if (start >= end) return ByteArray(0)
            val result = ByteArray((end - start).toInt())
            var position = 0L
            for (part in parts) {
                val partEnd = position + part.size
                val from = maxOf(start, position)
                val to = minOf(end, partEnd)
                if (from < to) {
                    when (part) {
                        is Stored -> part.bytes.copyInto(result, (from - start).toInt(), (from - position).toInt(), (to - position).toInt())
                        is Fill -> result.fill(part.value.toByte(), (from - start).toInt(), (to - start).toInt())
                    }
                }
                position = partEnd
            }
            return result
        }
    }

    class Output(val reference: JsonElement, val chunkCount: Int, val largestChunk: Int)

    fun output(chunks: Sequence<ByteArray>): Output {
        val builder = ContentBuilder()
        val digest = MessageDigest.getInstance("SHA-256")
        var chunkCount = 0
        var largestChunk = 0
        var size = 0L
        for (chunk in chunks) {
            builder.bytes(chunk)
            digest.update(chunk)
            chunkCount += 1
            largestChunk = maxOf(largestChunk, chunk.size)
            size += chunk.size
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val parts = builder.build()
        val only = parts.singleOrNull()
        val reference = if (parts.isEmpty() || only is Stored) {
            JsonObject(
                mapOf(
                    "file" to JsonPrimitive(storedPath((only as? Stored)?.bytes ?: ByteArray(0))),
                    "size" to JsonPrimitive(size),
                    "sha256" to JsonPrimitive(sha256),
                ),
            )
        } else {
            var offset = 0L
            val segments = parts.map { part ->
                val fields = mutableMapOf<String, JsonElement>("offset" to JsonPrimitive(offset), "size" to JsonPrimitive(part.size))
                when (part) {
                    is Fill -> fields["fill"] = JsonPrimitive(part.value)
                    is Stored -> fields["file"] = JsonPrimitive(storedPath(part.bytes))
                }
                offset += part.size
                JsonObject(fields)
            }
            JsonObject(mapOf("size" to JsonPrimitive(size), "sha256" to JsonPrimitive(sha256), "segments" to JsonArray(segments)))
        }
        return Output(reference, chunkCount, largestChunk)
    }

    private fun storedPath(bytes: ByteArray): String {
        val sha256 = GoldenReplay.sha256(bytes)
        val matches = outputsBySha[sha256].orEmpty()
        return matches.singleOrNull() ?: "no single file under $OUTPUTS_DIRECTORY has sha256 $sha256: $matches"
    }

    private class ContentBuilder {
        private val segments = mutableListOf<Part>()
        private var pending = mutableListOf<ByteArray>()

        private fun flushBytes() {
            if (pending.isEmpty()) return
            val joined = ByteArray(pending.sumOf { it.size })
            var offset = 0
            for (part in pending) {
                part.copyInto(joined, offset)
                offset += part.size
            }
            segments += Stored(joined)
            pending = mutableListOf()
        }

        fun fill(value: Int, size: Long) {
            if (size == 0L) return
            val last = segments.lastOrNull()
            if (pending.isEmpty() && last is Fill && last.value == value) {
                segments[segments.size - 1] = Fill(value, last.size + size)
                return
            }
            if (size < FILL_MIN_RUN) {
                bytes(ByteArray(size.toInt()) { value.toByte() })
                return
            }
            flushBytes()
            segments += Fill(value, size)
        }

        fun bytes(bytes: ByteArray) {
            var start = 0
            var position = 0
            while (position < bytes.size) {
                val value = bytes[position].toInt() and 0xff
                var end = position + 1
                while (end < bytes.size && bytes[end] == bytes[position]) end += 1
                val last = segments.lastOrNull()
                val extendsFill = position == 0 && pending.isEmpty() && last is Fill && last.value == value
                if (extendsFill || end - position >= FILL_MIN_RUN) {
                    if (position > start) pending += bytes.copyOfRange(start, position)
                    fill(value, (end - position).toLong())
                    if (end - position < FILL_MIN_RUN) flushBytes()
                    start = end
                }
                position = end
            }
            if (start < bytes.size) pending += bytes.copyOfRange(start, bytes.size)
        }

        fun build(): List<Part> {
            flushBytes()
            return segments.toList()
        }
    }
}
