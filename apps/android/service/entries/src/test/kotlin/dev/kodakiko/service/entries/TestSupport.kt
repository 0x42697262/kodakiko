@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.entries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ENTRY_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.SignupMode
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
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.data.media.decryptWhole
import dev.kodakiko.js.JsDate
import dev.kodakiko.media.ByteSource
import dev.kodakiko.media.MediaFormat
import dev.kodakiko.media.ResolvedTime
import dev.kodakiko.media.TimeSource
import dev.kodakiko.media.byteSourceFromBytes
import dev.kodakiko.service.account.DatabaseRowStore
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.kodakiko.data.media.MediaKind as PlatformMediaKind

const val FIRST_NOW_ISO = "2026-11-12T10:00:00.000Z"
const val STEP_MS = 60_000L
const val FIXTURE_ID_BYTES = 16
const val FIXTURE_USER_ID = "user"
const val FIXTURE_ACCOUNT_PREFIX = "fixture/account-"
const val FIXTURE_THUMBNAIL_BYTES = 64L
const val FIXTURE_PHOTO_SIDE = 100L
const val FIXTURE_THUMBNAIL_SIDE = 10L
const val FIXTURE_PHOTO_MIME = "image/jpeg"
const val OTHER_USER = "someone-else-locally"
const val TEST_TIME_ZONE = "Europe/Paris"
const val TEST_TIMEZONE_OFFSET_MINUTES = -60L
const val TEST_DEVICE_OFFSET_MINUTES = 60L
const val TEST_DEFAULT_MAX_MEDIA = 20L
const val TEST_MAX_UPLOAD_BYTES = 3_758_096_384L
const val TEST_TRAY_LIMIT = 20
const val TEST_PROBE_WIDTH = 640
const val TEST_PROBE_HEIGHT = 480
const val TEST_VIDEO_DURATION_MS = 2000L
const val TEST_THUMBNAIL_BASE64URL = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD-JaQAA3AAAAAA"
const val TEST_THUMBNAIL_SIDE = 1
const val WAIT_SECONDS = 10L
const val ENTRY_ID_FIELD = "entryId"
const val MEDIA_ID_FIELD = "mediaId"
const val HARNESS_PHOTO_NAME = "photo.jpg"
const val COORDINATE_DELTA = 0.000005
val FIRST_NOW_MS: Long = JsDate.parse(FIRST_NOW_ISO).toLong()
val PARIS = TestGps(48.8584, 2.2945)
private val FAKE_JPEG_HEADER = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte())

val repoRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
}

fun fixtureMedia(name: String): File = File(repoRoot, "tests/fixtures/media/$name")

fun fakeJpeg(size: Int, fill: Int = 7): ByteArray {
    val bytes = ByteArray(size) { fill.toByte() }
    FAKE_JPEG_HEADER.copyInto(bytes)
    return bytes
}

fun randomIdOf(sodium: Sodium): () -> String = { encodeBytes(sodium.randomBytes(FIXTURE_ID_BYTES)) }

fun sequentialIds(): () -> String {
    val counter = AtomicInteger(0)
    return { "id" + counter.incrementAndGet().toString().padStart(20, '0') }
}

fun isWiped(key: ByteArray): Boolean = key.all { it == 0.toByte() }

fun entryPayload(entryId: String) = buildJsonObject { put(ENTRY_ID_FIELD, entryId) }

fun entryMediaPayload(entryId: String, mediaId: String) = buildJsonObject {
    put(ENTRY_ID_FIELD, entryId)
    put(MEDIA_ID_FIELD, mediaId)
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

fun testConfig(maxMediaPerEntry: Long = TEST_DEFAULT_MAX_MEDIA, maxUploadBytes: Long = TEST_MAX_UPLOAD_BYTES): PublicConfigDto =
    PublicConfigDto(
        signupMode = SignupMode.INVITE,
        sharingEnabled = false,
        publicLinksEnabled = false,
        maxMediaPerEntry = maxMediaPerEntry,
        maxUploadBytes = maxUploadBytes,
        defaultQuotaBytes = 0,
        trashDays = 30,
        accountDeletionGraceDays = 7,
        serverVersion = "test",
    )

fun settingsWith(encryptThumbnails: Boolean): DeviceSettings = DeviceSettingsDefaults.ANDROID.copy(encryptThumbnails = encryptThumbnails)

class FixedDeviceSettings(private val settings: DeviceSettings, private val isLoaded: Boolean = true) : DeviceSettingsSource {
    override fun loaded(): Boolean = isLoaded

    override fun get(): DeviceSettings = settings
}

class TestClock(
    @Volatile private var nowMs: Long = FIRST_NOW_MS,
    private val offsetMinutes: Long = TEST_TIMEZONE_OFFSET_MINUTES,
    private val zone: String? = TEST_TIME_ZONE,
) : EntriesClock {
    override fun now(): Long = nowMs

    override fun nowIso(): String = JsDate.toIsoString(nowMs.toDouble())

    override fun timezoneOffsetMinutes(epochMs: Long): Long = offsetMinutes

    override fun timeZone(): String? = zone

    fun advance(milliseconds: Long) {
        nowMs += milliseconds
    }
}

fun openMetadataForTest(sodium: Sodium, entryKey: ByteArray, entryId: String, blob: ByteArray): EntryMetadataV1 =
    openPayload(sodium, entryKey, blob, ContractSchemas.entryMetadataV1, ENTRY_METADATA_SCHEMA_VERSION, emptyMap(), AdContext.ENTRY_METADATA, entryId)

fun sealMetadataForTest(sodium: Sodium, entryKey: ByteArray, entryId: String, metadata: EntryMetadataV1): ByteArray =
    sealPayload(sodium, entryKey, metadata, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId)

fun variantKeyForTest(sodium: Sodium, mediaKey: ByteArray, variant: ObjectVariant): ByteArray =
    sodium.kdfDerive(
        KEY_BYTES,
        if (variant == ObjectVariant.ORIGINAL) KdfSubkeyIds.MEDIA_ORIGINAL else KdfSubkeyIds.MEDIA_THUMBNAIL,
        KdfContext.MEDIA.value,
        mediaKey,
    )

fun decryptStoredObject(sodium: Sodium, fileStore: FileStore, objectId: String, mediaKey: ByteArray, variant: ObjectVariant, plaintextSize: Long): ByteArray {
    val size = fileStore.size(objectId) ?: error("missing object $objectId")
    return decryptWhole(sodium, variantKeyForTest(sodium, mediaKey, variant), fileStore.readRange(objectId, 0, size.toInt()), plaintextSize)
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
)

class LocalEntry(val entryId: String, val entryKey: ByteArray, val mediaIds: List<String>, val metadata: EntryMetadataV1)

fun fixtureDescriptor(mediaId: String, plaintextBytes: Long): MediaDescriptorV1 =
    MediaDescriptorV1(
        mediaId = mediaId,
        kind = MediaKind.PHOTO,
        mimeType = FIXTURE_PHOTO_MIME,
        width = FIXTURE_PHOTO_SIDE,
        height = FIXTURE_PHOTO_SIDE,
        durationMs = null,
        originalPlaintextBytes = plaintextBytes,
        thumbnail = ThumbnailDescriptor(width = FIXTURE_THUMBNAIL_SIDE, height = FIXTURE_THUMBNAIL_SIDE, plaintextBytes = FIXTURE_THUMBNAIL_BYTES),
        capturedAtUtc = null,
        originalFileName = null,
    )

fun createLocalEntry(fixture: AccountFixture, mediaBytes: List<ByteArray>, note: String = "", tags: List<String> = emptyList()): LocalEntry {
    val sodium = fixture.sodium
    val entryId = fixture.randomId()
    val entryKey = sodium.randomBytes(KEY_BYTES)
    val mediaIds = mutableListOf<String>()
    val descriptors = mutableListOf<MediaDescriptorV1>()
    val mediaRows = mutableListOf<MediaRow>()
    val objectRows = mutableListOf<ObjectRow>()
    for (plaintext in mediaBytes) {
        val mediaId = fixture.randomId()
        val mediaKey = sodium.randomBytes(KEY_BYTES)
        mediaRows += MediaRow(mediaId, entryId, wrapKey(sodium, entryKey, mediaKey, AdContext.MEDIA_KEY_BY_ENTRY, "$entryId/$mediaId"))
        for ((variant, size) in listOf(ObjectVariant.ORIGINAL to plaintext.size.toLong(), ObjectVariant.THUMBNAIL to FIXTURE_THUMBNAIL_BYTES)) {
            objectRows += ObjectRow(
                id = fixture.randomId(),
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
        descriptors += fixtureDescriptor(mediaId, plaintext.size.toLong())
    }
    val metadata = EntryMetadataV1(
        takenAtUtc = FIRST_NOW_ISO,
        utcOffsetMinutes = 0,
        timeZone = null,
        note = note,
        tags = tags,
        location = null,
        media = descriptors,
        createdAtUtc = FIRST_NOW_ISO,
        editedAtUtc = FIRST_NOW_ISO,
    )
    val metadataBlob = sealMetadataForTest(sodium, entryKey, entryId, metadata)
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
                createdAt = FIRST_NOW_ISO,
                updatedAt = FIRST_NOW_ISO,
                localState = EntryLocalState.PENDING_CREATE,
            ),
        )
        tx.memberships.put(MembershipRow(fixture.homeCollectionId, entryId, entryKeyWrap, 0))
        mediaRows.forEach(tx.media::put)
        objectRows.forEach(tx.objects::put)
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
    return LocalEntry(entryId, entryKey, mediaIds, metadata)
}

fun collectionRow(id: String, kind: CollectionKind, ownerId: String, role: CollectionRole, keyMaterial: ByteArray): CollectionRow =
    CollectionRow(
        id = id,
        kind = kind,
        ownerId = ownerId,
        ownerUsername = if (ownerId == FIXTURE_USER_ID) "tester" else "other",
        role = role,
        keyMaterial = keyMaterial,
        metadataBlob = byteArrayOf(1),
        metaUpdatedAt = FIRST_NOW_ISO,
        serverLastSeq = 0,
        cursorSeq = 0,
    )

class GatedRowStore(private val inner: RowStorePort, private val holdFirstOnly: Boolean) : RowStorePort by inner {
    val entered = CountDownLatch(1)
    private val gate = CountDownLatch(1)
    private val held = AtomicBoolean(false)

    override fun <T> transaction(work: (RowStoreTx) -> T): T {
        val result = inner.transaction(work)
        if (!holdFirstOnly || held.compareAndSet(false, true)) {
            entered.countDown()
            gate.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        return result
    }

    fun release() {
        gate.countDown()
    }
}

class ToolkitCalls {
    val byteSource = AtomicInteger(0)
    val probe = AtomicInteger(0)
    val makeThumbnail = AtomicInteger(0)
    val posterTimes: MutableList<Long> = CopyOnWriteArrayList()
    val discarded: MutableList<MediaInput> = CopyOnWriteArrayList()
}

class ScriptedToolkit(
    private val bytes: ByteArray,
    private val probeResult: ProbeResult,
    private val modified: String?,
    private val thumbnail: ThumbnailResult,
) : MediaToolkit {
    val calls = ToolkitCalls()

    override fun byteSource(input: MediaInput): ByteSource {
        calls.byteSource.incrementAndGet()
        return byteSourceFromBytes(bytes)
    }

    override fun probe(input: MediaInput, format: MediaFormat): ProbeResult {
        calls.probe.incrementAndGet()
        return probeResult
    }

    override fun makeThumbnail(input: MediaInput, format: MediaFormat, posterTimeMs: Long): ThumbnailResult {
        calls.makeThumbnail.incrementAndGet()
        calls.posterTimes += posterTimeMs
        return thumbnail
    }

    override fun modifiedUtc(input: MediaInput): String? = modified

    override fun discard(input: MediaInput) {
        calls.discarded += input
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

class FakePlatformMedia(private val probeResult: ProbeResult, private val thumbnail: ThumbnailResult) : PlatformMedia {
    val probed: MutableList<Pair<File, PlatformMediaKind>> = CopyOnWriteArrayList()
    val thumbnails: MutableList<Triple<File, PlatformMediaKind, Long>> = CopyOnWriteArrayList()

    override fun probe(file: File, kind: PlatformMediaKind): ProbeResult {
        probed += file to kind
        return probeResult
    }

    override fun thumbnail(file: File, kind: PlatformMediaKind, posterTimeMs: Long): ThumbnailResult {
        thumbnails += Triple(file, kind, posterTimeMs)
        return thumbnail
    }
}

class DiskFullException : IOException("disk full")

class FailingObjectWrites(private val inner: EntryFiles, private val failingCall: Int) : EntryFiles by inner {
    private val calls = AtomicInteger(0)

    override fun writeObject(objectId: String, chunks: Sequence<ByteArray>): Long {
        if (calls.incrementAndGet() == failingCall) throw DiskFullException()
        return inner.writeObject(objectId, chunks)
    }
}

class FailingPlainThumbnails(private val inner: EntryFiles) : EntryFiles by inner {
    override fun writePlainThumbnail(mediaId: String, bytes: ByteArray) {
        throw DiskFullException()
    }
}

class HarnessHooks {
    @Volatile
    var beforeKey: (suspend () -> Unit)? = null

    @Volatile
    var failEnqueue: Boolean = false

    val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var maxMediaPerEntry: Long = TEST_DEFAULT_MAX_MEDIA
}

class EnqueueFailure : IllegalStateException("enqueue failed in the harness")

class HookedEntryKeys(private val inner: EntryKeys, private val hooks: HarnessHooks) : EntryKeys by inner {
    override suspend fun collectionKey(collectionId: String): ByteArray {
        hooks.beforeKey?.invoke()
        return inner.collectionKey(collectionId)
    }

    override suspend fun entryKey(entryId: String): ByteArray {
        hooks.beforeKey?.invoke()
        return inner.entryKey(entryId)
    }
}

class HookedRowStore(private val inner: RowStorePort, private val hooks: HarnessHooks) : RowStorePort by inner {
    override fun <T> transaction(work: (RowStoreTx) -> T): T =
        inner.transaction { tx ->
            val lastBefore = tx.outbox.all().maxOfOrNull { it.id } ?: 0L
            val result = work(tx)
            if (hooks.failEnqueue && tx.outbox.all().any { it.id > lastBefore }) throw EnqueueFailure()
            result
        }
}

class EntryRows(val entry: EntryRow?, val memberships: List<MembershipRow>, val media: List<MediaRow>, val objects: List<ObjectRow>)

abstract class EntriesTestBase {
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
            tx.collections.put(collectionRow(homeCollectionId, CollectionKind.HOME, FIXTURE_USER_ID, CollectionRole.OWNER, keyMaterial))
        }
        return AccountFixture(sodium, keys, FIXTURE_USER_ID, homeCollectionId, homeCollectionKey, rowStore, fileStore, randomId)
    }

    protected fun TestScope.harness(): EntriesHarness =
        EntriesHarness(context, sodium, createAccountFixture(), UnconfinedTestDispatcher(testScheduler))

    protected fun openFileStore(): FileStore = FileStore.open(context, accountHash(sodium, "host/user-" + randomIdOf(sodium)()))

    @After
    fun closeRowStores() {
        rowStores.forEach { it.close() }
    }
}

class EntriesHarness(
    val context: Context,
    val sodium: Sodium,
    val fixture: AccountFixture,
    ioDispatcher: CoroutineDispatcher,
) {
    val hooks = HarnessHooks()
    val clock = TestClock()
    val notified = AtomicInteger(0)
    val changed: MutableList<List<String>> = CopyOnWriteArrayList()
    val files: EntryFiles = FileStoreEntryFiles { fixture.fileStore }

    @Volatile
    var locked: Boolean = false

    val entryKeys: EntryKeys = EntryKeyCache(
        EntryKeysDeps(
            sodium = sodium,
            rowStore = fixture.rowStore,
            account = { if (locked) null else fixture.keys },
            ioDispatcher = ioDispatcher,
        ),
    )

    val tray = CaptureTray(CaptureTrayDeps(files = files))

    val pipeline: ImportPipeline = DefaultImportPipeline(
        ImportPipelineDeps(
            sodium = sodium,
            files = files,
            toolkit = InMemoryToolkit(),
            clock = clock,
            config = { null },
            deviceSettings = FixedDeviceSettings(settingsWith(encryptThumbnails = true)),
            randomId = fixture.randomId,
            ioDispatcher = Dispatchers.IO,
        ),
    )

    val service: EntriesService = DefaultEntriesService(
        EntriesServiceDeps(
            sodium = sodium,
            rowStore = HookedRowStore(fixture.rowStore, hooks),
            files = files,
            entryKeys = HookedEntryKeys(entryKeys, hooks),
            captureTray = tray,
            config = { testConfig(maxMediaPerEntry = hooks.maxMediaPerEntry) },
            sync = object : EntriesSyncPort {
                override fun notifyLocalWrite() {
                    notified.incrementAndGet()
                }

                override fun isEntryInFlight(entryId: String): Boolean = entryId in hooks.inFlight
            },
            onEntriesChanged = { changed += it.toList() },
            clock = clock,
            randomId = fixture.randomId,
            userId = { if (locked) null else fixture.userId },
            ioDispatcher = ioDispatcher,
        ),
    )

    init {
        fixture.rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(fixture.homeCollectionId))
    }

    fun lock() {
        locked = true
        entryKeys.clear()
    }

    fun inputFile(bytes: ByteArray): File {
        val file = File.createTempFile("harness-", ".bin", context.cacheDir)
        file.writeBytes(bytes)
        return file
    }

    fun pickerInput(bytes: ByteArray, fileName: String = HARNESS_PHOTO_NAME): MediaInput = MediaInput(MediaOrigin.PICKER, inputFile(bytes), fileName, null)

    suspend fun importIntoTray(bytes: ByteArray, fileName: String = HARNESS_PHOTO_NAME): String {
        val outcome = pipeline.importOne(pickerInput(bytes, fileName), ImportSlots(tray.items.size, TEST_TRAY_LIMIT))
        if (outcome !is ImportOutcome.Imported) error("import refused: $outcome")
        tray.add(outcome.media)
        return outcome.media.mediaId
    }

    suspend fun createEntry(mediaBytes: List<ByteArray>, note: String = "note", tags: List<String> = emptyList()): String {
        for (bytes in mediaBytes) importIntoTray(bytes)
        return service.createFromTray(CreateEntryInput(note = note, tags = tags, time = null))
    }

    suspend fun metadataOf(entryId: String): EntryMetadataV1 = service.readMetadata(entryId)

    fun entryRow(entryId: String): EntryRow = fixture.rowStore.transaction { it.entries.get(entryId) } ?: error("no entry $entryId")

    fun outbox(): List<OutboxRow> = fixture.rowStore.transaction { it.outbox.all() }

    fun outboxKinds(): List<String> = outbox().map { it.kind }

    fun entryCount(): Int = fixture.rowStore.transaction { it.entries.all().size }

    fun rowsOf(entryId: String): EntryRows =
        fixture.rowStore.transaction { tx ->
            val media = tx.media.byEntry(entryId)
            EntryRows(tx.entries.get(entryId), tx.memberships.byEntry(entryId), media, media.flatMap { tx.objects.byMedia(it.id) })
        }

    fun markSynced(entryId: String) {
        fixture.rowStore.transaction { tx ->
            val row = tx.entries.get(entryId) ?: error("no entry $entryId")
            tx.entries.put(row.copy(localState = EntryLocalState.SYNCED, baseRevision = 1, baseMetadataBlob = row.metadataBlob))
            tx.outbox.all().forEach { tx.outbox.delete(it.id) }
        }
    }

    fun setOwner(entryId: String, ownerId: String) {
        fixture.rowStore.transaction { tx -> tx.entries.get(entryId)?.let { tx.entries.put(it.copy(ownerId = ownerId)) } }
    }

    fun setAttempts(kind: String, attempts: Long) {
        fixture.rowStore.transaction { tx -> tx.outbox.all().filter { it.kind == kind }.forEach { tx.outbox.update(it.copy(attempts = attempts)) } }
    }

    suspend fun writeConcurrentNote(entryId: String, note: String) {
        val entryKey = entryKeys.entryKey(entryId)
        val row = entryRow(entryId)
        val current = openMetadataForTest(sodium, entryKey, entryId, row.metadataBlob)
        val blob = sealMetadataForTest(sodium, entryKey, entryId, current.copy(note = note))
        fixture.rowStore.transaction { tx -> tx.entries.get(entryId)?.let { tx.entries.put(it.copy(metadataBlob = blob)) } }
    }
}

fun nowTime(): ResolvedTime = ResolvedTime(FIRST_NOW_ISO, 0, TimeSource.NOW)
