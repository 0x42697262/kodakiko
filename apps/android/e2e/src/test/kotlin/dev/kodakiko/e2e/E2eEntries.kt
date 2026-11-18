package dev.kodakiko.e2e

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.NewOutboxRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.media.decryptWhole
import dev.kodakiko.data.net.ObjectCalls
import dev.kodakiko.domain.openEntryMetadata
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.sync.SystemSyncClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.SecureRandom

data class EntryInput(val mediaBytes: List<ByteArray>, val note: String, val tags: List<String>)

data class CreatedEntry(val entryId: String, val mediaIds: List<String>)

data class EntrySnapshot(val revision: Long, val blob: String)

enum class BlobSide { LOCAL, BASE }

private val bucketClient: OkHttpClient by lazy { OkHttpClient() }
private val secureRandom = SecureRandom()

fun randomMedia(sizeBytes: Int): ByteArray {
    val bytes = ByteArray(sizeBytes)
    secureRandom.nextBytes(bytes)
    E2eConstants.JPEG_START_OF_SCAN.copyInto(bytes)
    return bytes
}

fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

suspend fun <T> E2eDevice.transaction(work: (dev.kodakiko.data.db.RowStoreTx) -> T): T = withContext(io) { rowStore.transaction(work) }

suspend fun E2eDevice.homeCollectionId(): String =
    transaction { tx -> tx.collections.all() }.firstOrNull { it.kind == CollectionKind.HOME }?.id ?: error("$name has no home collection yet")

suspend fun E2eDevice.importIntoTray(files: List<Pair<ByteArray, String>>): List<String> {
    val mediaIds = mutableListOf<String>()
    for ((bytes, fileName) in files) {
        val file = withContext(io) {
            File.createTempFile(E2eConstants.IMPORT_TEMP_PREFIX, E2eConstants.MEDIA_FILE_SUFFIX, context.cacheDir).also { it.writeBytes(bytes) }
        }
        val outcome = try {
            mediaImport.importOne(
                MediaInput(MediaOrigin.PICKER, file, fileName, null),
                ImportSlots(captureTray.items.size, E2eConstants.SEED_TRAY_LIMIT),
            )
        } finally {
            withContext(io) { file.delete() }
        }
        if (outcome !is ImportOutcome.Imported) error("$fileName: $outcome")
        captureTray.add(outcome.media)
        mediaIds += outcome.media.mediaId
    }
    return mediaIds
}

suspend fun E2eDevice.createEntryLocally(input: EntryInput): CreatedEntry {
    val mediaIds = importIntoTray(
        input.mediaBytes.mapIndexed { position, bytes -> bytes to E2eConstants.MEDIA_FILE_PREFIX + position + E2eConstants.MEDIA_FILE_SUFFIX },
    )
    val entryId = entries.createFromTray(CreateEntryInput(note = input.note, tags = input.tags, time = null))
    return CreatedEntry(entryId, mediaIds)
}

suspend fun E2eDevice.readEntry(entryId: String): EntryRow? = transaction { tx -> tx.entries.get(entryId) }

suspend fun E2eDevice.readMetadata(entryId: String, which: BlobSide = BlobSide.LOCAL): EntryMetadataV1 {
    val entry = readEntry(entryId) ?: error("$name has no entry $entryId")
    val blob = when (which) {
        BlobSide.LOCAL -> entry.metadataBlob
        BlobSide.BASE -> entry.baseMetadataBlob ?: error("$name has no base blob for $entryId")
    }
    val entryKey = entryKeys.entryKey(entryId)
    return openEntryMetadata(sodium, entryKey, entryId, blob)
}

private suspend fun E2eDevice.enqueue(kind: String, entryId: String) {
    transaction { tx ->
        tx.outbox.enqueue(
            NewOutboxRow(
                kind = kind,
                payload = buildJsonObject { put(E2eConstants.ENTRY_ID_FIELD, entryId) },
                state = OutboxState.QUEUED,
                createdAt = SystemSyncClock.now(),
                attempts = 0,
                nextAttemptAt = 0,
                lastErrorCode = null,
            ),
        )
    }
}

suspend fun E2eDevice.editEntryLocally(entryId: String, change: (EntryMetadataV1) -> EntryMetadataV1, editedAtUtc: String) {
    val entryKey = entryKeys.entryKey(entryId)
    val current = readMetadata(entryId)
    val next = change(current).copy(editedAtUtc = editedAtUtc)
    val blob = sealPayload(sodium, entryKey, next, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId)
    transaction { tx ->
        val entry = tx.entries.get(entryId) ?: error("$name has no entry $entryId")
        tx.entries.put(
            entry.copy(
                metadataBlob = blob,
                localState = if (entry.localState == EntryLocalState.SYNCED) EntryLocalState.PENDING_UPDATE else entry.localState,
            ),
        )
    }
    enqueue(E2eConstants.UPDATE_ENTRY_META_KIND, entryId)
}

suspend fun E2eDevice.trashEntryLocally(entryId: String) {
    transaction { tx ->
        val entry = tx.entries.get(entryId) ?: error("$name has no entry $entryId")
        tx.entries.put(entry.copy(trashedAt = SystemSyncClock.nowIso()))
    }
    enqueue(E2eConstants.TRASH_ENTRY_KIND, entryId)
}

suspend fun E2eDevice.deleteEntryLocally(entryId: String) {
    enqueue(E2eConstants.DELETE_ENTRY_KIND, entryId)
}

suspend fun E2eDevice.outboxRows(): List<OutboxRow> = transaction { tx -> tx.outbox.all() }

suspend fun E2eDevice.syncUntilIdle() {
    val deadline = System.currentTimeMillis() + E2eConstants.SETTLE_DEADLINE_MS
    while (true) {
        sync.syncNow()
        val queued = outboxRows().filter { it.state == OutboxState.QUEUED }
        if (queued.isEmpty()) return
        if (System.currentTimeMillis() > deadline) {
            error("$name still has ${queued.joinToString(", ") { "${it.kind}#${it.id}" }} queued")
        }
        delay(E2eConstants.SETTLE_POLL_MS)
    }
}

suspend fun E2eDevice.entrySnapshots(): Map<String, EntrySnapshot> =
    transaction { tx -> tx.entries.all() }.associate { it.id to EntrySnapshot(it.revision, hexOf(it.metadataBlob)) }

suspend fun E2eDevice.downloadCiphertext(mediaId: String, variant: ObjectVariant): Pair<dev.kodakiko.data.db.ObjectRow, ByteArray> {
    val objectRow = transaction { tx -> tx.objects.byMedia(mediaId) }.firstOrNull { it.variant == variant }
        ?: error("$name has no $variant object for $mediaId")
    val reference = withContext(io) { http.request(ObjectCalls.download(objectRow.id)) }
    val ciphertext = withContext(io) {
        bucketClient.newCall(Request.Builder().url(reference.url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "bucket GET failed: ${response.code}" }
            response.body.bytes()
        }
    }
    return objectRow to ciphertext
}

suspend fun E2eDevice.downloadAndDecrypt(entryId: String, mediaId: String, variant: ObjectVariant): ByteArray {
    val (objectRow, ciphertext) = downloadCiphertext(mediaId, variant)
    val variantKey = entryKeys.variantKey(entryId, mediaId, variant)
    try {
        return decryptWhole(sodium, variantKey, ciphertext, objectRow.plaintextSize)
    } finally {
        wipe(variantKey)
    }
}

suspend fun E2eDevice.entryIds(): List<String> = transaction { tx -> tx.entries.all() }.map { it.id }.sorted()
