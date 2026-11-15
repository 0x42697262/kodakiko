@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ENTRY_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.EntryDto
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.contract.openPayload
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.createStreamEncryptor
import dev.kodakiko.crypto.streamLayout
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryPurpose
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.NewOutboxRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.EmptyBodySchema
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadStatus
import dev.kodakiko.js.JsDate
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.MediaFormat
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.CaptureTrayDeps
import dev.kodakiko.service.entries.CreateEntryInput
import dev.kodakiko.service.entries.DefaultEntriesService
import dev.kodakiko.service.entries.DefaultImportPipeline
import dev.kodakiko.service.entries.DeviceSettingsSource
import dev.kodakiko.service.entries.EntriesClock
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.EntriesServiceDeps
import dev.kodakiko.service.entries.EntriesSyncPort
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.service.entries.EntryKeyCache
import dev.kodakiko.service.entries.EntryKeys
import dev.kodakiko.service.entries.EntryKeysDeps
import dev.kodakiko.service.entries.FileStoreEntryFiles
import dev.kodakiko.service.entries.ImportOutcome
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.ImportPipelineDeps
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.service.entries.MediaToolkit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole
import dev.kodakiko.contract.EntryPurpose as WireEntryPurpose

const val NOW_ISO = "2026-11-12T10:00:00.000Z"
const val FIXTURE_ID_BYTES = 16
const val FIXTURE_USER_ID = "user"
const val FIXTURE_USERNAME = "tester"
const val FIXTURE_ACCOUNT_PREFIX = "fixture/account-"
const val FIXTURE_THUMBNAIL_BYTES = 64
const val FIXTURE_PHOTO_SIDE = 100L
const val FIXTURE_THUMBNAIL_SIDE = 10L
const val FIXTURE_PHOTO_MIME = "image/jpeg"
const val ENTRY_ID_FIELD = "entryId"
const val MEDIA_ID_FIELD = "mediaId"
const val GET = "GET"
const val POST = "POST"
const val PATCH = "PATCH"
const val DELETE = "DELETE"
const val OWNER_ID = "OOOOOOOOOOOOOOOOOOOOOO"
const val STEP_MS = 60_000L
const val PHOTO_BYTES = 300
const val TEST_PROBE_WIDTH = 640
const val TEST_PROBE_HEIGHT = 480
const val TEST_VIDEO_DURATION_MS = 2000L
const val TEST_THUMBNAIL_BASE64URL = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD-JaQAA3AAAAAA"
const val TEST_THUMBNAIL_SIDE = 1
const val TEST_TRAY_LIMIT = 20
const val HARNESS_PHOTO_NAME = "photo.jpg"
val FIRST_NOW_MS: Long = JsDate.parse(NOW_ISO).toLong()
private val FAKE_JPEG_HEADER = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte())

class Bug(message: String = "bug") : RuntimeException(message)

fun randomIdOf(sodium: Sodium): () -> String = { encodeBytes(sodium.randomBytes(FIXTURE_ID_BYTES)) }

fun entryPayload(entryId: String): JsonObject = buildJsonObject { put(ENTRY_ID_FIELD, entryId) }

fun entryMediaPayload(entryId: String, mediaId: String): JsonObject = buildJsonObject {
    put(ENTRY_ID_FIELD, entryId)
    put(MEDIA_ID_FIELD, mediaId)
}

fun fakeJpeg(size: Int, fill: Int = 7): ByteArray {
    val bytes = ByteArray(size) { fill.toByte() }
    FAKE_JPEG_HEADER.copyInto(bytes)
    return bytes
}

suspend fun failureOf(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        failure
    }

suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit): T {
    val failure = failureOf(block)
    assertTrue("expected ${T::class.java.simpleName}, got $failure", failure is T)
    return failure as T
}

fun apiFailure(status: Int, code: ErrorCode, details: JsonElement? = null, retryAfterSeconds: Double? = null): ApiError =
    ApiError(status, code, details, null, retryAfterSeconds)

data class RecordedCall(val method: String, val path: String, val body: JsonElement?)

private class FakeRoute(val method: String, val path: String?, val pattern: Regex?, val handler: (RecordedCall) -> JsonElement?) {
    fun matches(call: RecordedCall): Boolean =
        method == call.method && (path?.let { it == call.path } ?: (pattern?.containsMatchIn(call.path) == true))
}

class FakeHttp : SyncHttp {
    val requests: MutableList<RecordedCall> = CopyOnWriteArrayList()
    private val routes = CopyOnWriteArrayList<FakeRoute>()

    fun on(method: String, path: String, handler: (RecordedCall) -> JsonElement?) {
        routes += FakeRoute(method, path, null, handler)
    }

    fun on(method: String, pattern: Regex, handler: (RecordedCall) -> JsonElement?) {
        routes += FakeRoute(method, null, pattern, handler)
    }

    fun count(method: String, path: String): Int = requests.count { it.method == method && it.path == path }

    fun count(method: String, pattern: Regex): Int = requests.count { it.method == method && pattern.containsMatchIn(it.path) }

    fun bodyAt(index: Int): JsonObject = requests[index].body?.jsonObject ?: error("request $index has no body")

    override fun <T> request(call: ApiCall<T>): T {
        val recorded = RecordedCall(call.method, call.path, call.body)
        requests += recorded
        val route = routes.lastOrNull { it.matches(recorded) } ?: throw apiFailure(404, ErrorCode.NOT_FOUND)
        val response = route.handler(recorded)
        val schema = call.responseSchema
        if (response == null && schema is EmptyBodySchema<T>) return schema.decodeEmpty()
        return schema.decode(response ?: JsonNull)
    }
}

fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

class FakeUploader : Uploader {
    val jobs: MutableList<ObjectUploadJob> = CopyOnWriteArrayList()
    private val listeners = CopyOnWriteArrayList<(UploadEvent) -> Unit>()
    private val known = ConcurrentHashMap<String, UploadStatus>()
    private val cancelCount = AtomicInteger(0)

    fun emit(event: UploadEvent) {
        listeners.forEach { it(event) }
    }

    fun setStatus(objectId: String, status: UploadStatus?) {
        if (status == null) known.remove(objectId) else known[objectId] = status
    }

    fun cancelled(): Int = cancelCount.get()

    override suspend fun enqueue(jobs: List<ObjectUploadJob>) {
        for (job in jobs) {
            this.jobs += job
            known[job.objectId] = UploadStatus.UPLOADING
        }
    }

    override suspend fun statuses(): Map<String, UploadStatus> = known.toMap()

    override fun subscribe(listener: (UploadEvent) -> Unit): () -> Unit {
        val entry: (UploadEvent) -> Unit = { listener(it) }
        listeners += entry
        return { listeners.remove(entry) }
    }

    override suspend fun cancelAll() {
        cancelCount.incrementAndGet()
        known.clear()
    }
}

class TestSyncClock(@Volatile var nowMs: Long, private val iso: (() -> String)? = null) : SyncClock {
    override fun now(): Long = nowMs

    override fun nowIso(): String = iso?.invoke() ?: JsDate.toIsoString(nowMs.toDouble())
}

class AccountFixture(
    val sodium: Sodium,
    val keys: AccountKeys,
    val userId: String,
    val homeCollectionId: String,
    val homeCollectionKey: ByteArray,
    val rowStore: DatabaseRowStore,
    val fileStore: FileStore,
    val randomId: () -> String,
) {
    val files: EntryFiles = FileStoreEntryFiles { fileStore }
}

class LocalEntry(
    val entryId: String,
    val entryKey: ByteArray,
    val mediaIds: List<String>,
    val originalObjectIds: List<String>,
    val thumbnailObjectIds: List<String>,
    val plaintexts: List<ByteArray>,
    val metadata: EntryMetadataV1,
)

fun descriptorOf(mediaId: String, plaintextBytes: Long): MediaDescriptorV1 =
    MediaDescriptorV1(
        mediaId = mediaId,
        kind = MediaKind.PHOTO,
        mimeType = FIXTURE_PHOTO_MIME,
        width = FIXTURE_PHOTO_SIDE,
        height = FIXTURE_PHOTO_SIDE,
        durationMs = null,
        originalPlaintextBytes = plaintextBytes,
        thumbnail = ThumbnailDescriptor(width = FIXTURE_THUMBNAIL_SIDE, height = FIXTURE_THUMBNAIL_SIDE, plaintextBytes = FIXTURE_THUMBNAIL_BYTES.toLong()),
        capturedAtUtc = null,
        originalFileName = null,
    )

fun sealEntryMetadata(fixture: AccountFixture, entryKey: ByteArray, entryId: String, metadata: EntryMetadataV1): ByteArray =
    sealPayload(fixture.sodium, entryKey, metadata, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId)

fun openEntryMetadataForTest(sodium: Sodium, entryKey: ByteArray, entryId: String, blob: ByteArray): EntryMetadataV1 =
    openPayload(sodium, entryKey, blob, ContractSchemas.entryMetadataV1, ENTRY_METADATA_SCHEMA_VERSION, emptyMap(), AdContext.ENTRY_METADATA, entryId)

private fun encryptedChunks(sodium: Sodium, variantKey: ByteArray, plaintext: ByteArray): Sequence<ByteArray> = sequence {
    val encryptor = createStreamEncryptor(sodium, variantKey, plaintext.size.toLong())
    yield(encryptor.header)
    val chunkSize = encryptor.layout.chunkSize
    for (index in 0 until encryptor.layout.chunkCount.toInt()) {
        val start = index * chunkSize
        val end = minOf(plaintext.size, start + chunkSize)
        yield(encryptor.encryptNext(plaintext.copyOfRange(start, end)))
    }
}

fun createLocalEntry(
    fixture: AccountFixture,
    mediaBytes: List<ByteArray>,
    note: String,
    tags: List<String>,
    enqueue: Boolean = true,
): LocalEntry {
    val sodium = fixture.sodium
    val entryId = fixture.randomId()
    val entryKey = sodium.randomBytes(KEY_BYTES)
    val mediaIds = mutableListOf<String>()
    val originalObjectIds = mutableListOf<String>()
    val thumbnailObjectIds = mutableListOf<String>()
    val descriptors = mutableListOf<MediaDescriptorV1>()
    val mediaRows = mutableListOf<MediaRow>()
    val objectRows = mutableListOf<ObjectRow>()
    for (plaintext in mediaBytes) {
        val mediaId = fixture.randomId()
        val mediaKey = sodium.randomBytes(KEY_BYTES)
        val originalKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_ORIGINAL, KdfContext.MEDIA.value, mediaKey)
        val thumbnailKey = sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_THUMBNAIL, KdfContext.MEDIA.value, mediaKey)
        val thumbnail = sodium.randomBytes(FIXTURE_THUMBNAIL_BYTES)
        val originalObjectId = fixture.randomId()
        val thumbnailObjectId = fixture.randomId()
        fixture.fileStore.writeObject(originalObjectId, encryptedChunks(sodium, originalKey, plaintext))
        fixture.fileStore.writeObject(thumbnailObjectId, encryptedChunks(sodium, thumbnailKey, thumbnail))
        mediaRows += MediaRow(mediaId, entryId, wrapKey(sodium, entryKey, mediaKey, AdContext.MEDIA_KEY_BY_ENTRY, "$entryId/$mediaId"))
        for ((objectId, variant, size) in listOf(
            Triple(originalObjectId, ObjectVariant.ORIGINAL, plaintext.size.toLong()),
            Triple(thumbnailObjectId, ObjectVariant.THUMBNAIL, FIXTURE_THUMBNAIL_BYTES.toLong()),
        )) {
            objectRows += ObjectRow(
                id = objectId,
                mediaId = mediaId,
                variant = variant,
                sizeBytes = streamLayout(size, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize,
                plaintextSize = size,
                localState = ObjectLocalState.PRESENT,
                uploadState = ObjectUploadState.LOCAL,
                uploadJobId = null,
                lastAccessAt = 0,
            )
        }
        mediaIds += mediaId
        originalObjectIds += originalObjectId
        thumbnailObjectIds += thumbnailObjectId
        descriptors += descriptorOf(mediaId, plaintext.size.toLong())
    }
    val metadata = EntryMetadataV1(
        takenAtUtc = NOW_ISO,
        utcOffsetMinutes = 0,
        timeZone = null,
        note = note,
        tags = tags,
        location = null,
        media = descriptors,
        createdAtUtc = NOW_ISO,
        editedAtUtc = NOW_ISO,
    )
    val metadataBlob = sealEntryMetadata(fixture, entryKey, entryId, metadata)
    val entryKeyWrap = wrapKey(sodium, fixture.homeCollectionKey, entryKey, AdContext.ENTRY_KEY_BY_COLLECTION, "${fixture.homeCollectionId}/$entryId")
    fixture.rowStore.transaction { tx ->
        tx.entries.put(
            EntryRow(
                id = entryId,
                ownerId = fixture.userId,
                purpose = EntryPurpose.JOURNAL,
                revision = 0,
                metadataBlob = metadataBlob,
                baseMetadataBlob = null,
                baseRevision = 0,
                serverPendingBlob = null,
                serverPendingRevision = null,
                trashedAt = null,
                createdAt = NOW_ISO,
                updatedAt = NOW_ISO,
                localState = EntryLocalState.PENDING_CREATE,
            ),
        )
        tx.memberships.put(MembershipRow(fixture.homeCollectionId, entryId, entryKeyWrap, 0))
        mediaRows.forEach(tx.media::put)
        objectRows.forEach(tx.objects::put)
        if (enqueue) {
            tx.outbox.enqueue(
                NewOutboxRow(
                    kind = "createEntry",
                    payload = entryPayload(entryId),
                    state = OutboxState.QUEUED,
                    createdAt = 0,
                    attempts = 0,
                    nextAttemptAt = 0,
                    lastErrorCode = null,
                ),
            )
        }
    }
    return LocalEntry(entryId, entryKey, mediaIds, originalObjectIds, thumbnailObjectIds, mediaBytes, metadata)
}

fun collectionRow(
    id: String,
    serverLastSeq: Long,
    cursorSeq: Long,
    ownerUsername: String = "t",
    keyMaterial: ByteArray = byteArrayOf(1),
    metaUpdatedAt: String = NOW_ISO,
): CollectionRow =
    CollectionRow(
        id = id,
        kind = CollectionKind.HOME,
        ownerId = OWNER_ID,
        ownerUsername = ownerUsername,
        role = CollectionRole.OWNER,
        keyMaterial = keyMaterial,
        metadataBlob = byteArrayOf(1),
        metaUpdatedAt = metaUpdatedAt,
        serverLastSeq = serverLastSeq,
        cursorSeq = cursorSeq,
    )

fun entryDtoJson(id: String, revision: Long, blob: ByteArray, trashedAt: String? = null, time: String = NOW_ISO): JsonElement =
    ContractSchemas.entryDto.encode(
        EntryDto(
            id = id,
            ownerId = OWNER_ID,
            purpose = WireEntryPurpose.JOURNAL,
            revision = revision,
            metadataBlob = encodeBytes(blob),
            trashedAt = trashedAt,
            createdAt = time,
            updatedAt = time,
        ),
    )

fun collectionDtoJson(
    collectionId: String,
    metaUpdatedAt: String = NOW_ISO,
    kind: WireCollectionKind = WireCollectionKind.HOME,
    role: WireCollectionRole = WireCollectionRole.OWNER,
): JsonElement =
    ContractSchemas.collectionDto.encode(
        CollectionDto(
            id = collectionId,
            kind = kind,
            role = role,
            ownerId = OWNER_ID,
            ownerUsername = FIXTURE_USERNAME,
            keyMaterial = encodeBytes(byteArrayOf(1, 2, 3)),
            metadataBlob = encodeBytes(byteArrayOf(4, 5, 6)),
            lastSeq = 1,
            createdAt = NOW_ISO,
            metaUpdatedAt = metaUpdatedAt,
        ),
    )

fun outboxRowOf(kind: String, payload: JsonElement): OutboxRow =
    OutboxRow(id = 1, kind = kind, payload = payload, state = OutboxState.QUEUED, createdAt = 0, attempts = 0, nextAttemptAt = 0, lastErrorCode = null)

fun noLog(): SyncLog = SyncLog { _: String, _: Map<String, Any?> -> }

class FixedDeviceSettings(private val settings: DeviceSettings) : DeviceSettingsSource {
    override fun loaded(): Boolean = true

    override fun get(): DeviceSettings = settings
}

class EntriesTwinClock(@Volatile private var nowMs: Long = FIRST_NOW_MS) : EntriesClock {
    override fun now(): Long = nowMs

    override fun nowIso(): String = JsDate.toIsoString(nowMs.toDouble())

    override fun timezoneOffsetMinutes(epochMs: Long): Long = 0

    override fun timeZone(): String? = null

    fun advance(milliseconds: Long) {
        nowMs += milliseconds
    }
}

class InMemoryToolkit : MediaToolkit {
    override fun byteSource(input: MediaInput): ByteSource = byteSourceFromBytes(input.file.readBytes())

    override fun probe(input: MediaInput, format: MediaFormat): ProbeResult =
        ProbeResult(TEST_PROBE_WIDTH, TEST_PROBE_HEIGHT, if (format.kind == MediaKind.VIDEO) TEST_VIDEO_DURATION_MS else null, true, 0)

    override fun makeThumbnail(input: MediaInput, format: MediaFormat, posterTimeMs: Long): ThumbnailResult =
        ThumbnailResult(decodeBytes(TEST_THUMBNAIL_BASE64URL), TEST_THUMBNAIL_SIDE, TEST_THUMBNAIL_SIDE)

    override fun modifiedUtc(input: MediaInput): String? = null

    override fun discard(input: MediaInput) {
        input.file.delete()
    }
}

class EntriesHarness(val context: Context, val sodium: Sodium, val fixture: AccountFixture, ioDispatcher: CoroutineDispatcher) {
    val clock = EntriesTwinClock()
    val files: EntryFiles = fixture.files

    val entryKeys: EntryKeys = EntryKeyCache(
        EntryKeysDeps(sodium = sodium, rowStore = fixture.rowStore, account = { fixture.keys }, ioDispatcher = ioDispatcher),
    )

    private val tray = CaptureTray(CaptureTrayDeps(files = files))

    private val pipeline: ImportPipeline = DefaultImportPipeline(
        ImportPipelineDeps(
            sodium = sodium,
            files = files,
            toolkit = InMemoryToolkit(),
            clock = clock,
            config = { null },
            deviceSettings = FixedDeviceSettings(DeviceSettingsDefaults.ANDROID),
            randomId = fixture.randomId,
            ioDispatcher = ioDispatcher,
        ),
    )

    val service: EntriesService = DefaultEntriesService(
        EntriesServiceDeps(
            sodium = sodium,
            rowStore = fixture.rowStore,
            files = files,
            entryKeys = entryKeys,
            captureTray = tray,
            config = { null },
            sync = object : EntriesSyncPort {
                override fun notifyLocalWrite() = Unit

                override fun isEntryInFlight(entryId: String): Boolean = false
            },
            onEntriesChanged = {},
            clock = clock,
            randomId = fixture.randomId,
            userId = { fixture.userId },
            ioDispatcher = ioDispatcher,
        ),
    )

    init {
        fixture.rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(fixture.homeCollectionId))
    }

    suspend fun createEntry(mediaBytes: List<ByteArray>, note: String): String {
        for (bytes in mediaBytes) {
            val file = File.createTempFile("harness-", ".bin", context.cacheDir)
            file.writeBytes(bytes)
            val outcome = pipeline.importOne(MediaInput(MediaOrigin.PICKER, file, HARNESS_PHOTO_NAME, null), ImportSlots(tray.items.size, TEST_TRAY_LIMIT))
            if (outcome !is ImportOutcome.Imported) error("import refused: $outcome")
            tray.add(outcome.media)
        }
        return service.createFromTray(CreateEntryInput(note = note, tags = emptyList(), time = null))
    }

    fun entryRow(entryId: String): EntryRow = fixture.rowStore.transaction { it.entries.get(entryId) } ?: error("no entry $entryId")

    fun outbox(): List<OutboxRow> = fixture.rowStore.transaction { it.outbox.all() }
}

abstract class SyncTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val sodium: Sodium = createSodium()
    private val rowStores = mutableListOf<DatabaseRowStore>()

    protected fun createAccountFixture(): AccountFixture {
        val randomId = randomIdOf(sodium)
        val accountKey = FIXTURE_ACCOUNT_PREFIX + randomId()
        val rowStore = DatabaseRowStore(context, sodium).also { rowStores += it }
        rowStore.open(accountKey)
        val fileStore = FileStore.open(context, accountHash(sodium, accountKey))
        val pair = sodium.boxKeypair()
        val keys = AccountKeys(masterKey = sodium.randomBytes(KEY_BYTES), privateKey = pair.privateKey, publicKey = pair.publicKey)
        val homeCollectionId = randomId()
        val homeCollectionKey = sodium.randomBytes(KEY_BYTES)
        val keyMaterial = wrapKey(sodium, keys.masterKey, homeCollectionKey, AdContext.COLLECTION_KEY_BY_MASTER, homeCollectionId)
        rowStore.transaction { tx ->
            tx.collections.put(
                CollectionRow(
                    id = homeCollectionId,
                    kind = CollectionKind.HOME,
                    ownerId = FIXTURE_USER_ID,
                    ownerUsername = FIXTURE_USERNAME,
                    role = CollectionRole.OWNER,
                    keyMaterial = keyMaterial,
                    metadataBlob = byteArrayOf(1),
                    metaUpdatedAt = NOW_ISO,
                    serverLastSeq = 0,
                    cursorSeq = 0,
                ),
            )
        }
        return AccountFixture(sodium, keys, FIXTURE_USER_ID, homeCollectionId, homeCollectionKey, rowStore, fileStore, randomId)
    }

    protected fun AccountFixture.entry(entryId: String): EntryRow? = rowStore.transaction { it.entries.get(entryId) }

    protected fun AccountFixture.outbox(): List<OutboxRow> = rowStore.transaction { it.outbox.all() }

    protected fun AccountFixture.objects(): List<ObjectRow> = rowStore.transaction { it.objects.all() }

    protected fun AccountFixture.setObjectStates(state: ObjectUploadState) {
        rowStore.transaction { tx -> tx.objects.all().forEach { tx.objects.put(it.copy(uploadState = state)) } }
    }

    protected fun AccountFixture.updateEntry(entryId: String, change: (EntryRow) -> EntryRow) {
        rowStore.transaction { tx -> tx.entries.get(entryId)?.let { tx.entries.put(change(it)) } }
    }

    @After
    fun closeRowStores() {
        rowStores.forEach { it.close() }
    }
}
