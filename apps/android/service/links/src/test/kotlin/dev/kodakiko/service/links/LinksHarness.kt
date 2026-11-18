package dev.kodakiko.service.links

import dev.kodakiko.crypto.STREAM_HEADER_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createStreamDecryptor
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.media.decryptWhole
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadStatus
import dev.kodakiko.service.sync.ObjectUploadJob
import dev.kodakiko.service.sync.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

val repoRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
}

val PARIS = TestGps(48.8584, 2.2945)

fun fixtureMedia(name: String): File = File(repoRoot, "tests/fixtures/media/$name")

fun uint32BigEndian(value: Long): ByteArray = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

fun seededBytes(length: Int, seed: Int): ByteArray {
    val bytes = ByteArray(length)
    var state = if (seed == 0) 1 else seed
    for (index in 0 until length) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        bytes[index] = state.toByte()
    }
    return bytes
}

fun stcoTrak(offset: Long): ByteArray =
    isoBox(
        "trak",
        isoBox("mdia", isoBox("minf", isoBox("stbl", isoBox("stco", joinBytes(byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 0, 1), uint32BigEndian(offset)))))),
    )

fun mp4WithLocation(): ByteArray {
    val payload = seededBytes(2048, 5)
    val head = ftypBox("isom", listOf("isom"))
    val build = { offset: Long -> moovBox(mvhdBox(3_800_000_000L), udtaBox(xyzBox("+48.8584+002.2945/")), stcoTrak(offset)) }
    val provisional = build(0)
    val offset = (head.size + provisional.size + 8).toLong()
    return joinBytes(head, build(offset), isoBox("mdat", payload))
}

fun mp4WithLocationDirectlyInMoov(): ByteArray {
    val payload = seededBytes(2048, 6)
    val head = ftypBox("isom", listOf("isom"))
    val build = { offset: Long -> moovBox(mvhdBox(3_800_000_000L), xyzBox("+48.8584+002.2945/"), stcoTrak(offset)) }
    val provisional = build(0)
    val offset = (head.size + provisional.size + 8).toLong()
    return joinBytes(head, build(offset), isoBox("mdat", payload))
}

fun fragmentedMp4(): ByteArray =
    joinBytes(ftypBox("isom", listOf("isom")), moovBox(mvhdBox(0)), isoBox("moof", ByteArray(16)), isoBox("mdat", seededBytes(64, 7)))

fun concatAll(parts: List<ByteArray>): ByteArray = joinBytes(*parts.toTypedArray())

fun decryptUploaded(sodium: Sodium, uploaded: Map<String, ByteArray>, objectId: String, key: ByteArray, plaintextSize: Long): ByteArray {
    val ciphertext = uploaded[objectId] ?: error("not uploaded $objectId")
    val decryptor = createStreamDecryptor(sodium, key, ciphertext.copyOfRange(0, STREAM_HEADER_BYTES), plaintextSize)
    val parts = mutableListOf<ByteArray>()
    val step = decryptor.layout.chunkCiphertextSize
    var offset = STREAM_HEADER_BYTES
    while (offset < ciphertext.size) {
        parts += decryptor.decryptNext(ciphertext.copyOfRange(offset, minOf(ciphertext.size, offset + step)))
        offset += step
    }
    decryptor.finish()
    return concatAll(parts)
}

fun EntriesHarness.objectRowsOfMedia(mediaId: String): List<ObjectRow> = fixture.rowStore.transaction { it.objects.byMedia(mediaId) }

suspend fun EntriesHarness.thumbnailPlaintext(entryId: String, mediaId: String): ByteArray {
    val row = objectRowsOfMedia(mediaId).firstOrNull { it.variant == ObjectVariant.THUMBNAIL } ?: error("no thumbnail for $mediaId")
    val key = entryKeys.variantKey(entryId, mediaId, ObjectVariant.THUMBNAIL)
    return decryptWhole(sodium, key, fixture.fileStore.readRange(row.id, 0, row.sizeBytes.toInt()), row.plaintextSize)
}

class FakeUploader(private val scope: CoroutineScope, private val fileStore: FileStore) : Uploader {
    val jobs: MutableList<ObjectUploadJob> = CopyOnWriteArrayList()
    val uploaded: MutableMap<String, ByteArray> = Collections.synchronizedMap(LinkedHashMap())
    private val listeners = CopyOnWriteArrayList<(UploadEvent) -> Unit>()

    @Volatile
    var eventsFor: (ObjectUploadJob) -> List<UploadEvent> = { listOf(UploadEvent.Completed(it.objectId)) }

    fun emit(event: UploadEvent) {
        listeners.forEach { it(event) }
    }

    fun listenerCount(): Int = listeners.size

    override suspend fun enqueue(jobs: List<ObjectUploadJob>) {
        for (job in jobs) {
            this.jobs += job
            val size = fileStore.size(job.objectId) ?: 0L
            uploaded[job.objectId] = fileStore.readRange(job.objectId, 0, size.toInt())
            val events = eventsFor(job)
            scope.launch { events.forEach(::emit) }
        }
    }

    override suspend fun statuses(): Map<String, UploadStatus> = emptyMap()

    override fun subscribe(listener: (UploadEvent) -> Unit): () -> Unit {
        val entry: (UploadEvent) -> Unit = { listener(it) }
        listeners += entry
        return { listeners.remove(entry) }
    }

    override suspend fun cancelAll() = Unit
}
