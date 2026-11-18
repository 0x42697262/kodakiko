package dev.kodakiko.data.media

import dev.kodakiko.crypto.CryptoException
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.createStreamEncryptor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random

class StreamFileReaderTest {
    private val sodium = createSodium()
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val mebibyte = 1 shl 20
    private val files = mutableListOf<File>()

    private fun plainByte(position: Long): Byte = ((position * 2_654_435_761L) ushr 13).toByte()

    private fun plain(start: Long, length: Int) = ByteArray(length) { plainByte(start + it) }

    private fun encrypt(plaintextSize: Long, variantKey: ByteArray = key): File {
        val file = Files.createTempFile("kodakiko-stream", ".bin").toFile().also { files += it }
        val encryptor = createStreamEncryptor(sodium, variantKey, plaintextSize)
        file.outputStream().buffered().use { out ->
            out.write(encryptor.header)
            var position = 0L
            while (!encryptor.isDone()) {
                val length = minOf(encryptor.layout.chunkSize.toLong(), plaintextSize - position).toInt()
                out.write(encryptor.encryptNext(plain(position, length)))
                position += length
            }
        }
        return file
    }

    private class CountingSource(private val inner: CiphertextSource) : CiphertextSource {
        val offsets = mutableListOf<Long>()

        override val size: Long get() = inner.size

        override fun read(offset: Long, length: Int): ByteArray {
            offsets += offset
            return inner.read(offset, length)
        }

        override fun close() = inner.close()
    }

    private fun chunkOffset(index: Int) = STREAM_HEADER_BYTES + index.toLong() * (mebibyte + 16)

    @After
    fun cleanup() {
        files.forEach { it.delete() }
    }

    @Test
    fun readsAnyRangeExactlyAndClampsLikeV031() {
        val size = 3L * mebibyte + mebibyte / 2
        StreamFileReader.open(sodium, CiphertextSource.of(encrypt(size)), key, size).use { reader ->
            assertEquals(size, reader.size)
            for ((offset, length) in listOf(0L to 10, mebibyte - 5L to 10, 0L to 2 * mebibyte + 3, size - 7 to 7, 123_456L to 1, 2L * mebibyte to mebibyte)) {
                assertArrayEquals("$offset+$length", plain(offset, length), reader.read(offset, length))
            }
            assertArrayEquals(plain(size - 3, 3), reader.read(size - 3, 100))
            assertArrayEquals(plain(0, 4), reader.read(-10, 4))
            assertEquals(0, reader.read(size, 10).size)
            assertEquals(0, reader.read(5, 0).size)
            assertEquals(0, reader.read(5, -1).size)
        }
    }

    @Test
    fun anEmptyObjectReadsNothing() {
        StreamFileReader.open(sodium, CiphertextSource.of(encrypt(0)), key, 0).use { reader ->
            assertEquals(0L, reader.size)
            assertEquals(0, reader.read(0, 10).size)
        }
    }

    @Test
    fun openProvesTheFinalChunkSoTruncationAndWrongKeysFailEarly() {
        val size = 2L * mebibyte + 100
        val whole = encrypt(size)
        val cut = Files.createTempFile("kodakiko-cut", ".bin").toFile().also { files += it }
        whole.copyTo(cut, overwrite = true)
        RandomAccessFile(cut, "rw").use { it.setLength(chunkOffset(2)) }
        assertThrows(CryptoException::class.java) { StreamFileReader.open(sodium, CiphertextSource.of(cut), key, size) }
        assertThrows(CryptoException::class.java) { StreamFileReader.open(sodium, CiphertextSource.of(cut), key, 2L * mebibyte) }
        assertThrows(DecryptionError::class.java) { StreamFileReader.open(sodium, CiphertextSource.of(whole), ByteArray(32) { 9 }, size) }
        assertThrows(CryptoException::class.java) { StreamFileReader.open(sodium, CiphertextSource.of(whole), key, size + 1) }
    }

    @Test
    fun aTamperedChunkFailsOnlyTheRangesThatTouchIt() {
        val size = 3L * mebibyte
        val file = encrypt(size)
        RandomAccessFile(file, "rw").use {
            it.seek(chunkOffset(1) + 10)
            val original = it.read()
            it.seek(chunkOffset(1) + 10)
            it.write(original xor 0x01)
        }
        StreamFileReader.open(sodium, CiphertextSource.of(file), key, size).use { reader ->
            assertArrayEquals(plain(0, 100), reader.read(0, 100))
            assertThrows(DecryptionError::class.java) { reader.read(mebibyte + 5L, 10) }
            assertArrayEquals(plain(2L * mebibyte, 100), reader.read(2L * mebibyte, 100))
        }
    }

    @Test
    fun keepsTheFourMostRecentlyUsedChunks() {
        val size = 8L * mebibyte + 10
        val source = CountingSource(CiphertextSource.of(encrypt(size)))
        StreamFileReader.open(sodium, source, key, size).use { reader ->
            for (index in listOf(0, 1, 2, 3, 0, 4, 5, 0, 1)) reader.read(index.toLong() * mebibyte, 1)
            val chunkReads = source.offsets.filter { it in (0..5).map(::chunkOffset) }
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 1).map(::chunkOffset), chunkReads)
        }
        assertEquals(4, MediaConstants.STREAM_CHUNK_CACHE_ITEMS)
    }

    @Test
    fun aClosedReaderRefusesToRead() {
        val size = 1000L
        val reader = StreamFileReader.open(sodium, CiphertextSource.of(encrypt(size)), key, size)
        reader.close()
        assertThrows(IllegalStateException::class.java) { reader.read(0, 10) }
    }

    @Test
    fun randomReadsInAOneGibibyteObjectAreExactAndFast() {
        val size = 1024L * mebibyte
        val random = Random(42)
        StreamFileReader.open(sodium, CiphertextSource.of(encrypt(size)), key, size).use { reader ->
            repeat(200) {
                val length = random.nextInt(1, 200 * 1024)
                val offset = random.nextLong(0, size - length)
                val started = System.nanoTime()
                val bytes = reader.read(offset, length)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertArrayEquals("$offset+$length", plain(offset, length), bytes)
                assertTrue("read $offset+$length took $elapsedMs ms", elapsedMs <= 300)
            }
        }
    }
}
