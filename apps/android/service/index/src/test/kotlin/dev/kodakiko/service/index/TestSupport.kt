@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.contract.MediaDescriptorV1
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.contract.ThumbnailDescriptor
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.CryptoException
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.streamLayout
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
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
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.service.account.DatabaseRowStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val NOW_ISO = "2026-11-12T10:00:00.000Z"
const val WAIT_MS = 2_000L
const val WAIT_INTERVAL_MS = 5L
const val ID_BYTES = 16
const val THUMBNAIL_BYTES = 64L
const val FIXTURE_USER_ID = "user"
const val FIXTURE_ACCOUNT_PREFIX = "fixture/account-"
const val PHOTO_MIME = "image/jpeg"
const val PHOTO_SIDE = 100L
const val THUMBNAIL_SIDE = 10L
const val CREATE_ENTRY_KIND = "createEntry"
const val ENTRY_ID_FIELD = "entryId"

val JOURNAL = GalleryFilter(
    scope = GalleryScope.Home,
    view = GalleryView.JOURNAL,
    tags = emptyList(),
    fromDate = null,
    toDate = null,
    text = "",
)

fun randomIdOf(sodium: Sodium): () -> String = { encodeBytes(sodium.randomBytes(ID_BYTES)) }

fun bytesOf(size: Int, fill: Int): ByteArray = ByteArray(size) { fill.toByte() }

class EntryKeyUnavailable : IllegalStateException()

class AccountFixture(
    val sodium: Sodium,
    val keys: AccountKeys,
    val userId: String,
    val homeCollectionId: String,
    val homeCollectionKey: ByteArray,
    val rowStore: DatabaseRowStore,
    val randomId: () -> String,
)

class LocalEntry(
    val entryId: String,
    val entryKey: ByteArray,
    val mediaIds: List<String>,
    val metadata: EntryMetadataV1,
)

fun createAccountFixture(sodium: Sodium, rowStore: DatabaseRowStore): AccountFixture {
    val randomId = randomIdOf(sodium)
    rowStore.open(FIXTURE_ACCOUNT_PREFIX + randomId())
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
                ownerUsername = "tester",
                role = CollectionRole.OWNER,
                keyMaterial = keyMaterial,
                metadataBlob = byteArrayOf(1),
                metaUpdatedAt = NOW_ISO,
                serverLastSeq = 0,
                cursorSeq = 0,
            ),
        )
    }
    return AccountFixture(sodium, keys, FIXTURE_USER_ID, homeCollectionId, homeCollectionKey, rowStore, randomId)
}

fun descriptorOf(mediaId: String, plaintextBytes: Long, caption: String? = null): MediaDescriptorV1 =
    MediaDescriptorV1(
        mediaId = mediaId,
        kind = MediaKind.PHOTO,
        mimeType = PHOTO_MIME,
        width = PHOTO_SIDE,
        height = PHOTO_SIDE,
        durationMs = null,
        originalPlaintextBytes = plaintextBytes,
        thumbnail = ThumbnailDescriptor(width = THUMBNAIL_SIDE, height = THUMBNAIL_SIDE, plaintextBytes = THUMBNAIL_BYTES),
        capturedAtUtc = null,
        originalFileName = null,
        caption = caption,
    )

fun metadataOf(note: String, tags: List<String>, media: List<MediaDescriptorV1>): EntryMetadataV1 =
    EntryMetadataV1(
        takenAtUtc = NOW_ISO,
        utcOffsetMinutes = 0,
        timeZone = null,
        note = note,
        tags = tags,
        location = null,
        media = media,
        createdAtUtc = NOW_ISO,
        editedAtUtc = NOW_ISO,
    )

fun sealEntryMetadata(sodium: Sodium, entryKey: ByteArray, entryId: String, metadata: EntryMetadataV1): ByteArray =
    sealPayload(sodium, entryKey, metadata, ContractSchemas.entryMetadataV1, AdContext.ENTRY_METADATA, entryId)

fun createLocalEntry(fixture: AccountFixture, mediaBytes: List<ByteArray>, note: String, tags: List<String>): LocalEntry {
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
        mediaRows.add(MediaRow(mediaId, entryId, wrapKey(sodium, entryKey, mediaKey, AdContext.MEDIA_KEY_BY_ENTRY, "$entryId/$mediaId")))
        for ((variant, size) in listOf(ObjectVariant.ORIGINAL to plaintext.size.toLong(), ObjectVariant.THUMBNAIL to THUMBNAIL_BYTES)) {
            objectRows.add(
                ObjectRow(
                    id = fixture.randomId(),
                    mediaId = mediaId,
                    variant = variant,
                    sizeBytes = streamLayout(size, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize,
                    plaintextSize = size,
                    localState = ObjectLocalState.PRESENT,
                    uploadState = ObjectUploadState.LOCAL,
                    uploadJobId = null,
                    lastAccessAt = 0,
                ),
            )
        }
        mediaIds.add(mediaId)
        descriptors.add(descriptorOf(mediaId, plaintext.size.toLong()))
    }
    val metadata = metadataOf(note, tags, descriptors)
    val metadataBlob = sealEntryMetadata(sodium, entryKey, entryId, metadata)
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
        tx.outbox.enqueue(
            NewOutboxRow(
                kind = CREATE_ENTRY_KIND,
                payload = buildJsonObject { put(ENTRY_ID_FIELD, entryId) },
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

class FixtureEntryKeys(private val fixture: AccountFixture) : EntryKeySource {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    var locked = false

    var beforeKey: suspend () -> Unit = {}

    override suspend fun entryKey(entryId: String): ByteArray {
        beforeKey()
        if (locked) throw AccountLockedException()
        cache[entryId]?.let { return it }
        val sodium = fixture.sodium
        val (memberships, collections) = fixture.rowStore.transaction { tx ->
            val rows = tx.memberships.byEntry(entryId)
            rows to rows.associate { it.collectionId to tx.collections.get(it.collectionId) }
        }
        for (membership in memberships.sortedByDescending { collections[it.collectionId]?.kind == CollectionKind.HOME }) {
            val collection = collections[membership.collectionId] ?: continue
            try {
                val collectionKey = unwrapKey(sodium, fixture.keys.masterKey, collection.keyMaterial, AdContext.COLLECTION_KEY_BY_MASTER, collection.id)
                val key = unwrapKey(sodium, collectionKey, membership.entryKeyWrap, AdContext.ENTRY_KEY_BY_COLLECTION, "${collection.id}/$entryId")
                cache[entryId] = key
                return key
            } catch (failure: CryptoException) {
                continue
            }
        }
        throw EntryKeyUnavailable()
    }
}

class TestEntryEvents : EntriesChangedSource {
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    fun emit(entryIds: List<String>) {
        listeners.toList().forEach { it(entryIds) }
    }

    override fun onEntriesChanged(listener: (List<String>) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}

fun TestScope.waitFor(timeoutMs: Long = WAIT_MS, intervalMs: Long = WAIT_INTERVAL_MS, check: () -> Unit) {
    val started = testScheduler.currentTime
    while (true) {
        runCurrent()
        try {
            check()
            return
        } catch (failure: AssertionError) {
            if (testScheduler.currentTime - started >= timeoutMs) throw failure
        }
        advanceTimeBy(intervalMs)
    }
}

abstract class IndexTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val sodium: Sodium = createSodium()
    private val rowStores = mutableListOf<DatabaseRowStore>()

    protected lateinit var fixture: AccountFixture
    protected lateinit var events: TestEntryEvents
    protected lateinit var entryKeys: FixtureEntryKeys
    protected lateinit var service: IndexService
    protected lateinit var entries: List<LocalEntry>
    protected val reports: MutableList<IndexBuildReport> = mutableListOf()

    protected var locked: Boolean
        get() = entryKeys.locked
        set(value) {
            entryKeys.locked = value
        }

    protected fun TestScope.setUpIndex() {
        val rowStore = DatabaseRowStore(context, sodium).also { rowStores.add(it) }
        fixture = createAccountFixture(sodium, rowStore)
        rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(fixture.homeCollectionId))
        events = TestEntryEvents()
        entryKeys = FixtureEntryKeys(fixture)
        service = DefaultIndexService(
            IndexServiceDeps(
                sodium = sodium,
                rowStore = rowStore,
                entryKeys = entryKeys,
                events = events,
                now = { testScheduler.currentTime },
                userId = { if (locked) null else fixture.userId },
                onBuilt = { reports.add(it) },
                scope = backgroundScope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ),
        )
        entries = listOf(listOf("beach"), listOf("beach", "lake"), listOf("city")).mapIndexed { position, tags ->
            createLocalEntry(fixture, listOf(bytesOf(32, position + 1)), "note number $position", tags)
        }
    }

    protected fun tamper(entryId: String) {
        fixture.rowStore.transaction { tx ->
            val row = tx.entries.get(entryId) ?: error("missing entry")
            val blob = row.metadataBlob.copyOf()
            blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
            tx.entries.put(row.copy(metadataBlob = blob))
        }
    }

    protected fun reseal(entry: LocalEntry, metadata: EntryMetadataV1) {
        val blob = sealEntryMetadata(sodium, entry.entryKey, entry.entryId, metadata)
        fixture.rowStore.transaction { tx ->
            val row = tx.entries.get(entry.entryId)
            if (row != null) tx.entries.put(row.copy(metadataBlob = blob))
        }
    }

    protected fun journalIds(): List<String> = service.index()?.query(JOURNAL)?.flatMap { it.entryIds } ?: emptyList()

    @After
    fun closeRowStores() {
        rowStores.forEach { it.close() }
    }
}
