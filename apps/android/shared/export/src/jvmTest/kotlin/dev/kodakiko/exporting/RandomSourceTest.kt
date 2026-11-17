package dev.kodakiko.exporting

import dev.kodakiko.crypto.MalformedObjectError
import dev.kodakiko.crypto.counterBytes
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.js.JsDate
import dev.kodakiko.testing.GoldenReplay
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val GOLDEN_PASSPHRASE = "a long export passphrase"

private class CountingSource(private val inner: RandomSource) : RandomSource {
    var largestRead = 0

    override val size: Long get() = inner.size

    override fun read(position: Long, length: Int): ByteArray {
        largestRead = maxOf(largestRead, length)
        return inner.read(position, length)
    }
}

class RandomSourceTest {
    private val sodium = createSodium()

    private fun golden(name: String): ByteArray = File(GoldenReplay.goldenDirectory, "export/kdkx/$name.kdkx").readBytes()

    private fun opened(file: ByteArray): RandomSource {
        val source = ByteArraySource(file)
        val key = deriveKdkxKey(sodium, GOLDEN_PASSPHRASE, readKdkxParams(source))
        return openKdkx(sodium, key, source)
    }

    private fun encrypted(plain: ByteArray): ByteArray {
        val params = KdkxParams(sodium.randomBytes(KDKX_SALT_BYTES), 2, 8_388_608)
        val writer = createKdkxWriter(sodium, deriveKdkxKey(sodium, GOLDEN_PASSPHRASE, params), params)
        val out = ByteArrayOutputStream()
        out.write(writer.header)
        writer.push(plain).forEach(out::write)
        out.write(writer.finish())
        return out.toByteArray()
    }

    @Test
    fun aGoldenMultiChunkKdkxReadsLikeTheWholeFileDecryptionAcrossTheChunkBoundary() {
        val file = golden("chunk-plus-10")
        val whole = decryptKdkx(sodium, deriveKdkxKey(sodium, GOLDEN_PASSPHRASE, readKdkxParams(file)), file)
        opened(file).use { source ->
            assertEquals(whole.size.toLong(), source.size)
            assertContentEquals(whole, source.readAll())
            assertContentEquals(whole.copyOfRange(KDKX_CHUNK_BYTES - 3, KDKX_CHUNK_BYTES + 7), source.read((KDKX_CHUNK_BYTES - 3).toLong(), 10))
        }
        opened(golden("five-bytes")).use { assertEquals(5L, it.size) }
    }

    @Test
    fun aWrongPassphraseOrATruncatedFileIsRefusedWhenOpened() {
        val file = golden("chunk-plus-10")
        val source = ByteArraySource(file)
        val wrong = deriveKdkxKey(sodium, "not the passphrase", readKdkxParams(source))
        assertFailsWith<Exception> { openKdkx(sodium, wrong, source) }
        assertFailsWith<MalformedObjectError> { opened(file.copyOf(file.size - 20)) }
    }

    @Test
    fun aZipInsideAKdkxIsListedAndCopiedInSlicesWithoutReadingTheWholeFile() {
        val media = counterBytes(7, 3 * KDKX_CHUNK_BYTES + 123)
        val zip = ByteArrayOutputStream()
        val writer = ZipWriter({ bytes: ByteArray -> zip.write(bytes) }, ZipTimeZone.UTC)
        writer.addFile("2026/01/a/entry.md", compress = true, mtime = JsDate.parse("2026-01-01T00:00:00.000Z")).push("note".encodeToByteArray(), true)
        writer.addFile("2026/01/a/001.mp4", compress = false, mtime = JsDate.parse("2026-01-01T00:00:00.000Z")).push(media, true)
        writer.finish()
        val counting = CountingSource(ByteArraySource(encrypted(zip.toByteArray())))
        val key = deriveKdkxKey(sodium, GOLDEN_PASSPHRASE, readKdkxParams(counting))
        openKdkx(sodium, key, counting).use { source ->
            val entries = listZip(source).associateBy { it.name }
            assertEquals("note", readZipEntry(source, entries.getValue("2026/01/a/entry.md")).decodeToString())
            val copied = ByteArrayOutputStream()
            var slices = 0
            copyZipEntry(source, entries.getValue("2026/01/a/001.mp4")) { slice ->
                assertTrue(slice.size <= KDKX_CHUNK_BYTES)
                copied.write(slice)
                slices += 1
            }
            assertContentEquals(media, copied.toByteArray())
            assertTrue(slices > 1)
        }
        assertTrue(counting.largestRead <= KDKX_CHUNK_BYTES + 16)
    }
}
