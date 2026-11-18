package dev.kodakiko.service.backup

import dev.kodakiko.crypto.createSodium
import dev.kodakiko.exporting.EXPORT_PART_MAX_BYTES
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException

private class TrackedStream : ByteArrayOutputStream() {
    var closed = false

    override fun close() {
        closed = true
        super.close()
    }
}

@RunWith(RobolectricTestRunner::class)
class SafExportDestinationTest {
    private val created = LinkedHashMap<String, Pair<String, TrackedStream>>()
    private val deleted = mutableListOf<String>()
    private var refuse = false

    private fun destination(partMaxBytes: Long = 4_000): SafExportDestination =
        SafExportDestination(
            partMaxBytes = partMaxBytes,
            createFile = { name: String, mimeType: String ->
                if (refuse) {
                    null
                } else {
                    TrackedStream().also { created[name] = mimeType to it }
                }
            },
            deleteFile = { name: String ->
                deleted += name
                true
            },
        )

    @Test
    fun eachPartIsAZipFileInTheChosenFolderThatReceivesTheBytesAndIsClosed() {
        val destination = destination()
        assertEquals(4_000L, destination.partMaxBytes)

        val part = destination.createPart("kodakiko-export-2026-11-12-part01.zip")
        part.write(byteArrayOf(1, 2))
        part.write(byteArrayOf(3))
        part.close()

        val (mimeType, stream) = created.getValue("kodakiko-export-2026-11-12-part01.zip")
        assertEquals("application/zip", mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.toByteArray())
        assertTrue(stream.closed)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun abortClosesThePartAndDeletesItsFile() {
        val part = destination().createPart("kodakiko-export-2026-11-12-part02.zip")
        part.write(byteArrayOf(9))

        part.abort()

        assertTrue(created.getValue("kodakiko-export-2026-11-12-part02.zip").second.closed)
        assertEquals(listOf("kodakiko-export-2026-11-12-part02.zip"), deleted)
    }

    @Test
    fun encryptedPartsKeepTheirKdkxNameAndTheZipType() {
        val inner = destination()
        val part = encryptedDestination(createSodium(), inner, "export passphrase", opsLimit = 2, memLimitBytes = 8_388_608).createPart("a.zip")
        part.close()
        assertEquals(listOf("a.zip.kdkx"), created.keys.toList())
        assertEquals("application/zip", created.getValue("a.zip.kdkx").first)
    }

    @Test
    fun aFolderThatRefusesTheFileFailsThePart() {
        refuse = true
        val failure = try {
            destination().createPart("kodakiko-export-2026-11-12-part01.zip")
            null
        } catch (error: IOException) {
            error
        }
        assertTrue(failure is ExportWriteException)
    }

    @Test
    fun theDefaultPartSizeIsTheExportMaximumBelowFourGibibytes() {
        val destination = SafExportDestination(createFile = { _: String, _: String -> TrackedStream() }, deleteFile = { _: String -> true })
        assertEquals(EXPORT_PART_MAX_BYTES, destination.partMaxBytes)
    }
}
