package dev.kodakiko.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovieDurationTest {
    private val fixtures: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            .resolve("tests/fixtures/media")

    private fun duration(name: String) = readMovieDurationMs(byteSourceFromBytes(File(fixtures, name).readBytes()))

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun mvhd(version: Int, timescale: Long, duration: Long): ByteArray {
        val fields = if (version == 1) {
            ByteArray(16) + uint32(timescale) + uint64(duration)
        } else {
            ByteArray(8) + uint32(timescale) + uint32(duration)
        }
        return box("mvhd", byteArrayOf(version.toByte(), 0, 0, 0) + fields + ByteArray(80))
    }

    private fun uint32(value: Long) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

    private fun uint64(value: Long) = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

    @Test
    fun readsThePresentationDurationOfTheFixtures() {
        assertEquals(20_000L, duration("video-20s.mp4"))
        assertEquals(3_000L, duration("video-gps.mov"))
        assertNull(duration("video-short.webm"))
        assertNull(duration("photo-gps.jpg"))
    }

    @Test
    fun handlesBothHeaderVersionsAndRoundsToTheNearestMillisecond() {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(4))
        assertEquals(1_500L, readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(0, 600, 900)))))
        assertEquals(10_000_000_000L, readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(1, 1_000, 10_000_000_000)))))
        assertEquals(33L, readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(0, 90_000, 2_970)))))
        assertEquals(34L, readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(0, 1_000_000, 33_500)))))
    }

    @Test
    fun unknownOrBrokenDurationsAreNull() {
        val ftyp = box("ftyp", "isom".toByteArray() + ByteArray(4))
        assertNull(readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(0, 0, 900)))))
        assertNull(readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(0, 600, 0xFFFFFFFFL)))))
        assertNull(readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(1, 600, -1L)))))
        assertNull(readMovieDurationMs(byteSourceFromBytes(ftyp + box("free", ByteArray(16)))))
        assertNull(readMovieDurationMs(byteSourceFromBytes(ftyp + box("moov", mvhd(0, 600, 900)).copyOfRange(0, 30))))
        assertNull(readMovieDurationMs(byteSourceFromBytes(ByteArray(0))))
    }
}
