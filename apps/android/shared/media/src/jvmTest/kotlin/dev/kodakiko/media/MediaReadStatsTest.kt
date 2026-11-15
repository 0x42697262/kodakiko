package dev.kodakiko.media

import dev.kodakiko.testing.GoldenReplay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaReadStatsTest {
    private class RecordingSource(private val inner: ByteSource) : ByteSource {
        var reads = 0L
        var bytesRequested = 0L
        var largestRead = 0L
        var furthestOffset = 0L

        override val size: Long get() = inner.size

        override fun read(offset: Long, length: Long): ByteArray {
            reads += 1
            bytesRequested += length
            largestRead = maxOf(largestRead, length)
            furthestOffset = maxOf(furthestOffset, offset + length)
            return inner.read(offset, length)
        }

        fun stats(): JsonObject = JsonObject(
            mapOf(
                "reads" to JsonPrimitive(reads),
                "bytesRequested" to JsonPrimitive(bytesRequested),
                "largestRead" to JsonPrimitive(largestRead),
                "furthestOffset" to JsonPrimitive(furthestOffset),
            ),
        )
    }

    @Test
    fun everyRecordedReadPatternIsReproduced() {
        val files = listOf("media/exif.json", "media/isobmff.json", "media/strip.json")
        val checked = files.flatMap { path -> GoldenReplay.cases(path).filter { it.containsKey("readStats") }.map { path to it } }
        assertTrue(checked.isNotEmpty())
        for ((path, case) in checked) {
            var recording: RecordingSource? = null
            MediaGoldenAdapter.call(case.getValue("fn").jsonPrimitive.content, case.getValue("args").jsonObject) { inner ->
                RecordingSource(inner).also { recording = it }
            }
            val name = case.getValue("name").jsonPrimitive.content
            val actual = requireNotNull(recording) { "$path: $name made no source" }.stats()
            assertTrue(GoldenReplay.equivalent(case.getValue("readStats"), actual), "$path: $name read stats $actual")
        }
        assertEquals(6, checked.size)
    }
}
