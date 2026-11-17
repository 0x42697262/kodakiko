@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.backup

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.counterBytes
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.exporting.ByteArraySource
import dev.kodakiko.exporting.KDKX_CHUNK_BYTES
import dev.kodakiko.exporting.KDKX_SALT_BYTES
import dev.kodakiko.exporting.KdkxParams
import dev.kodakiko.exporting.RandomSource
import dev.kodakiko.exporting.ZipTimeZone
import dev.kodakiko.exporting.ZipWriter
import dev.kodakiko.exporting.createKdkxWriter
import dev.kodakiko.exporting.deriveKdkxKey
import dev.kodakiko.js.JsDate
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportedMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

private const val PASSPHRASE = "v031-backup-passphrase"
private const val READ_LIMIT = KDKX_CHUNK_BYTES + 16
private const val NOTE = "---\nid: big0000000000000000000\ndate: 2026-02-01T10:30:00+01:00\ntags: [trip]\n" +
    "media: [{ file: 001.mp4, original_name: big.mp4, kind: video }]\n---\n\nbig video\n"

private class BoundedSource(private val inner: RandomSource) : RandomSource {
    var closed = false

    override val size: Long get() = inner.size

    override fun read(position: Long, length: Int): ByteArray {
        check(length <= READ_LIMIT) { "read of $length bytes" }
        return inner.read(position, length)
    }

    override fun close() {
        closed = true
    }
}

private class Recorder(sodium: Sodium, io: CoroutineDispatcher) {
    val media = mutableListOf<Pair<String, ByteArray>>()
    val largestSlice = intArrayOf(0)
    val created = mutableListOf<CreateEntryInput>()
    val favorites = mutableListOf<String>()

    val service = DefaultRestoreService(
        RestoreServiceDeps(
            sodium = sodium,
            importMedia = { file: RestoreMediaFile, _: Int ->
                val out = ByteArrayOutputStream()
                file.copyTo { slice ->
                    largestSlice[0] = maxOf(largestSlice[0], slice.size)
                    out.write(slice)
                }
                media += file.fileName to out.toByteArray()
                ImportOutcome.Imported(fakeImportedMedia("m${media.size}", file.fileName))
            },
            createEntry = { input: CreateEntryInput, _: List<ImportedMedia> ->
                created += input
                "e${created.size}"
            },
            setFavorite = { ids: List<String>, favorite: Boolean -> if (favorite) favorites += ids },
            setCaptions = { _: String, _: Map<String, String> -> },
            existingEntries = { emptyList() },
            ioDispatcher = io,
        ),
    )
}

@RunWith(RobolectricTestRunner::class)
class RestoreLargeBackupTest {
    private val sodium: Sodium = createSodium()

    private fun TestScope.recorder(): Recorder = Recorder(sodium, UnconfinedTestDispatcher(testScheduler))

    private fun encrypted(plain: ByteArray): ByteArray {
        val params = KdkxParams(sodium.randomBytes(KDKX_SALT_BYTES), 2, 8_388_608)
        val writer = createKdkxWriter(sodium, deriveKdkxKey(sodium, PASSPHRASE, params), params)
        return joinBytes(writer.header, *writer.push(plain).toTypedArray(), writer.finish())
    }

    @Test
    fun anEncryptedBackupIsRestoredWithBoundedReadsInsteadOfLoadingTheWholeFileIntoMemory() = runTest {
        val video = counterBytes(3, 4 * KDKX_CHUNK_BYTES + 77)
        val zip = ByteArrayOutputStream()
        val writer = ZipWriter({ bytes: ByteArray -> zip.write(bytes) }, ZipTimeZone.UTC)
        val mtime = JsDate.parse("2026-02-01T09:30:00.000Z")
        writer.addFile("2026/02/2026-02-01_1030_big00000/entry.md", compress = true, mtime = mtime).push(NOTE.encodeToByteArray(), true)
        writer.addFile("2026/02/2026-02-01_1030_big00000/001.mp4", compress = false, mtime = mtime).push(video, true)
        writer.finish()
        val source = BoundedSource(ByteArraySource(encrypted(zip.toByteArray())))
        val recorder = recorder()

        val summary = recorder.service.restore(listOf(RestoreFile("big.zip.kdkx", source)), PASSPHRASE) { }

        assertEquals(RestoreSummary(entries = 1, media = 1, duplicates = 0, failed = emptyList()), summary)
        assertEquals("big.mp4", recorder.media.single().first)
        assertArrayEquals(video, recorder.media.single().second)
        assertTrue(recorder.largestSlice[0] <= KDKX_CHUNK_BYTES)
    }

    @Test
    fun aKdkxMadeByTheV031ExportCodeRestoresEveryEntry() = runTest {
        val file = checkNotNull(javaClass.classLoader?.getResourceAsStream("v031-export-part01.zip.kdkx")).use { it.readBytes() }
        val recorder = recorder()

        val summary = recorder.service.restore(listOf(RestoreFile("kodakiko-export-2026-11-10-part01.zip.kdkx", file)), PASSPHRASE) { }

        assertEquals(RestoreSummary(entries = 2, media = 2, duplicates = 0, failed = emptyList()), summary)
        assertEquals(listOf("v0.3.1 entry 0", "v0.3.1 entry 1"), recorder.created.map { it.note })
        assertEquals(listOf("clip-0.webm", "clip-1.webm"), recorder.media.map { it.first })
        assertEquals(listOf(14_642, 14_642), recorder.media.map { it.second.size })
        assertEquals(listOf("e1"), recorder.favorites)
        val wrong = expectFailure<RestorePassphraseException> {
            recorder.service.restore(listOf(RestoreFile("part.zip.kdkx", file)), "not the passphrase") { }
        }
        assertEquals("part.zip.kdkx", wrong.fileName)
    }
}
