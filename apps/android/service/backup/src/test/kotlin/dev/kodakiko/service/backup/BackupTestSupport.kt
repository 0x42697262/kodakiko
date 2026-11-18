package dev.kodakiko.service.backup

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.media.decryptWhole
import dev.kodakiko.exporting.decryptKdkx
import dev.kodakiko.exporting.deriveKdkxKey
import dev.kodakiko.exporting.readKdkxParams
import dev.kodakiko.exporting.readZip
import dev.kodakiko.service.lock.AutoLock
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class MemoryPart(val name: String) {
    val chunks: MutableList<ByteArray> = CopyOnWriteArrayList()

    @Volatile
    var closed: Boolean = false

    @Volatile
    var aborted: Boolean = false

    fun bytes(): ByteArray = joinBytes(*chunks.toTypedArray())

    fun files(): Map<String, ByteArray> = readZip(bytes()).associate { it.name to it.content }

    fun names(): List<String> = readZip(bytes()).map { it.name }
}

class MemoryDestination(
    override val partMaxBytes: Long,
    private val onWrite: (MemoryPart) -> Unit = {},
    private val failWritesTo: String? = null,
) : ExportDestination {
    val parts: MutableList<MemoryPart> = CopyOnWriteArrayList()

    override fun createPart(name: String): ExportPart {
        val part = MemoryPart(name)
        parts += part
        return object : ExportPart {
            override fun write(bytes: ByteArray) {
                if (failWritesTo != null && name.endsWith(failWritesTo)) throw IOException("disk full")
                part.chunks += bytes.copyOf()
                onWrite(part)
            }

            override fun close() {
                part.closed = true
            }

            override fun abort() {
                part.aborted = true
            }
        }
    }
}

class CountingAutoLock : AutoLock {
    @Volatile
    var paused: Int = 0

    @Volatile
    var resumed: Int = 0

    override fun start() = Unit

    override fun stop() = Unit

    override fun pause() {
        paused += 1
    }

    override fun resume() {
        resumed += 1
    }
}

fun utf8(bytes: ByteArray?): String = bytes?.decodeToString() ?: ""

suspend fun EntriesHarness.originalPlaintext(entryId: String, mediaId: String): ByteArray {
    val row = originalRow(mediaId)
    val key = entryKeys.variantKey(entryId, mediaId, ObjectVariant.ORIGINAL)
    return decryptWhole(sodium, key, fixture.fileStore.readRange(row.id, 0, row.sizeBytes.toInt()), row.plaintextSize)
}

fun EntriesHarness.allEntryIds(): List<String> = fixture.rowStore.transaction { tx -> tx.entries.all().map { it.id } }

fun decryptKdkxFile(sodium: Sodium, file: ByteArray, passphrase: String): ByteArray =
    decryptKdkx(sodium, deriveKdkxKey(sodium, passphrase, readKdkxParams(file)), file)

fun readZipOfKdkx(sodium: Sodium, file: ByteArray, passphrase: String): Map<String, ByteArray> =
    readZip(decryptKdkxFile(sodium, file, passphrase)).associate { it.name to it.content }
