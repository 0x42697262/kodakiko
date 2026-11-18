@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sharing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.media.ProbeResult
import dev.kodakiko.data.media.ThumbnailResult
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.EmptyBodySchema
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
import dev.kodakiko.service.sync.SyncClock
import dev.kodakiko.service.sync.SyncHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

const val FIRST_NOW_ISO = "2026-11-12T10:00:00.000Z"
const val FIXTURE_ID_BYTES = 16
const val FIXTURE_USER_ID = "user"
const val FIXTURE_USERNAME = "tester"
const val FIXTURE_ACCOUNT_PREFIX = "fixture/account-"
const val GET = "GET"
const val POST = "POST"
const val PATCH = "PATCH"
const val DELETE = "DELETE"
const val TEST_PROBE_WIDTH = 640
const val TEST_PROBE_HEIGHT = 480
const val TEST_VIDEO_DURATION_MS = 2000L
const val TEST_THUMBNAIL_BASE64URL = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD-JaQAA3AAAAAA"
const val TEST_THUMBNAIL_SIDE = 1
const val TEST_TRAY_LIMIT = 20
const val HARNESS_PHOTO_NAME = "photo.jpg"
val FIRST_NOW_MS: Long = JsDate.parse(FIRST_NOW_ISO).toLong()
private val FAKE_JPEG_HEADER = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte())

fun fakeJpeg(size: Int, fill: Int = 7): ByteArray {
    val bytes = ByteArray(size) { fill.toByte() }
    FAKE_JPEG_HEADER.copyInto(bytes)
    return bytes
}

fun randomIdOf(sodium: Sodium): () -> String = { encodeBytes(sodium.randomBytes(FIXTURE_ID_BYTES)) }

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

fun apiFailure(status: Int, code: ErrorCode): ApiError = ApiError(status, code, null, null, null)

data class RecordedCall(val method: String, val path: String, val body: JsonElement?) {
    fun json(): JsonObject = body?.jsonObject ?: error("$method $path has no body")

    fun string(name: String): String = json().getValue(name).jsonPrimitive.content
}

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

    fun find(method: String, path: String): List<RecordedCall> = requests.filter { it.method == method && it.path == path }

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

class HarnessClock(@Volatile private var nowMs: Long = FIRST_NOW_MS) :
    EntriesClock,
    SyncClock {
    override fun now(): Long = nowMs

    override fun nowIso(): String = JsDate.toIsoString(nowMs.toDouble())

    override fun timezoneOffsetMinutes(epochMs: Long): Long = 0

    override fun timeZone(): String? = null

    fun advance(milliseconds: Long) {
        nowMs += milliseconds
    }
}

class FixedDeviceSettings(private val settings: DeviceSettings) : DeviceSettingsSource {
    override fun loaded(): Boolean = true

    override fun get(): DeviceSettings = settings
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
    val clock = HarnessClock()
    val files: EntryFiles = FileStoreEntryFiles { fixture.fileStore }

    @Volatile
    var locked: Boolean = false

    val entryKeys: EntryKeys = EntryKeyCache(
        EntryKeysDeps(sodium = sodium, rowStore = fixture.rowStore, account = { if (locked) null else fixture.keys }, ioDispatcher = ioDispatcher),
    )

    private val tray = CaptureTray(CaptureTrayDeps(files = files))

    private val pipeline: ImportPipeline = DefaultImportPipeline(
        ImportPipelineDeps(
            sodium = sodium,
            files = files,
            toolkit = InMemoryToolkit(),
            clock = clock,
            config = { null },
            deviceSettings = FixedDeviceSettings(DeviceSettingsDefaults.ANDROID.copy(encryptThumbnails = true)),
            randomId = fixture.randomId,
            ioDispatcher = Dispatchers.IO,
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
            userId = { if (locked) null else fixture.userId },
            ioDispatcher = ioDispatcher,
        ),
    )

    init {
        fixture.rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(fixture.homeCollectionId))
    }

    suspend fun createEntry(mediaBytes: List<ByteArray>, note: String = "note", tags: List<String> = emptyList()): String {
        for (bytes in mediaBytes) {
            val file = File.createTempFile("harness-", ".bin", context.cacheDir)
            file.writeBytes(bytes)
            val outcome = pipeline.importOne(MediaInput(MediaOrigin.PICKER, file, HARNESS_PHOTO_NAME, null), ImportSlots(tray.items.size, TEST_TRAY_LIMIT))
            if (outcome !is ImportOutcome.Imported) error("import refused: $outcome")
            tray.add(outcome.media)
        }
        return service.createFromTray(CreateEntryInput(note = note, tags = tags, time = null))
    }

    fun markSynced(entryId: String) {
        fixture.rowStore.transaction { tx ->
            val row = tx.entries.get(entryId) ?: error("no entry $entryId")
            tx.entries.put(row.copy(localState = EntryLocalState.SYNCED, baseRevision = 1, baseMetadataBlob = row.metadataBlob))
            tx.outbox.all().forEach { tx.outbox.delete(it.id) }
        }
    }

    fun entryRow(entryId: String): EntryRow? = fixture.rowStore.transaction { it.entries.get(entryId) }

    fun collectionRow(collectionId: String): CollectionRow? = fixture.rowStore.transaction { it.collections.get(collectionId) }

    fun objectsOf(entryId: String): List<ObjectRow> =
        fixture.rowStore.transaction { tx -> tx.media.byEntry(entryId).flatMap { tx.objects.byMedia(it.id) } }
}

abstract class HarnessTestBase {
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
                    metaUpdatedAt = FIRST_NOW_ISO,
                    serverLastSeq = 0,
                    cursorSeq = 0,
                ),
            )
        }
        return AccountFixture(sodium, keys, FIXTURE_USER_ID, homeCollectionId, homeCollectionKey, rowStore, fileStore, randomId)
    }

    @After
    fun closeRowStores() {
        rowStores.forEach { it.close() }
    }
}
