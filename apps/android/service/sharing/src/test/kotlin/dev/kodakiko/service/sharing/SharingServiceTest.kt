@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sharing

import dev.kodakiko.contract.COLLECTION_METADATA_SCHEMA_VERSION
import dev.kodakiko.contract.CollectionDto
import dev.kodakiko.contract.CollectionMetadataV1
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.PublicKeyResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.contract.openPayload
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.openSharePayload
import dev.kodakiko.crypto.publicKeyFingerprint
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.net.NetworkError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole

const val NOW = "2026-06-01T10:00:00.000Z"
const val WRAPPED_BYTES = 73
val SERVER_OWNER_ID = "U".repeat(22)
val RECIPIENT_ID = "B".repeat(22)
val OTHER_OWNER_ID = "O".repeat(22)

fun emptyObject(): JsonElement = JsonObject(emptyMap())

fun collectionDtoFor(request: RecordedCall): JsonElement {
    val body = ContractSchemas.collectionCreateRequest.decode(request.json())
    return ContractSchemas.collectionDto.encode(
        CollectionDto(
            id = body.id,
            kind = WireCollectionKind.SHARED,
            role = WireCollectionRole.OWNER,
            ownerId = SERVER_OWNER_ID,
            ownerUsername = FIXTURE_USERNAME,
            keyMaterial = body.keyByOwner,
            metadataBlob = body.metadataBlob,
            lastSeq = 0,
            createdAt = NOW,
            metaUpdatedAt = NOW,
            recipientCount = 0,
        ),
    )
}

fun publicKeyJson(userId: String, username: String, publicKey: ByteArray): JsonElement =
    ContractSchemas.publicKeyResponse.encode(PublicKeyResponse(userId, username, encodeBytes(publicKey)))

open class SharingTestBase : HarnessTestBase() {
    protected lateinit var harness: EntriesHarness
    protected lateinit var fake: FakeHttp
    protected lateinit var service: SharingService
    protected val changed: MutableList<List<String>> = CopyOnWriteArrayList()

    @Volatile
    protected var sharingEnabled: Boolean = true

    @Volatile
    protected var signedIn: Boolean = true

    protected fun sharingTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            val io = UnconfinedTestDispatcher(testScheduler)
            harness = EntriesHarness(context, sodium, createAccountFixture(), io)
            fake = FakeHttp()
            fake.on(POST, Routes.collections()) { request: RecordedCall -> collectionDtoFor(request) }
            service = DefaultSharingService(
                SharingServiceDeps(
                    sodium = sodium,
                    http = fake,
                    rowStore = harness.fixture.rowStore,
                    files = harness.files,
                    entryKeys = harness.entryKeys,
                    account = { if (signedIn) SharingAccount(harness.fixture.userId, harness.fixture.keys) else null },
                    sharingEnabled = { sharingEnabled },
                    randomId = harness.fixture.randomId,
                    clock = harness.clock,
                    afterChange = { entryIds: List<String> -> changed += entryIds.toList() },
                    ioDispatcher = io,
                ),
            )
            body()
        }

    protected suspend fun journal(name: String = "Trip"): String = service.createJournal(name)

    protected suspend fun syncedEntry(fill: Int): String {
        val entryId = harness.createEntry(listOf(fakeJpeg(1000, fill)))
        harness.markSynced(entryId)
        return entryId
    }

    protected fun openCollectionName(collectionKey: ByteArray, collectionId: String, blob: ByteArray): CollectionMetadataV1 =
        openPayload(
            sodium,
            collectionKey,
            blob,
            ContractSchemas.collectionMetadataV1,
            COLLECTION_METADATA_SCHEMA_VERSION,
            emptyMap(),
            AdContext.COLLECTION_METADATA,
            collectionId,
        )

    protected fun sealCollectionName(collectionKey: ByteArray, collectionId: String, name: String, createdAtUtc: String): ByteArray =
        sealPayload(sodium, collectionKey, CollectionMetadataV1(name, createdAtUtc), ContractSchemas.collectionMetadataV1, AdContext.COLLECTION_METADATA, collectionId)
}

@RunWith(RobolectricTestRunner::class)
class SharingServiceTest : SharingTestBase() {
    @Test
    fun createJournalSendsA73ByteKeyThatUnwrapsToTheCollectionKeyAndASealedName() = sharingTest {
        val id = journal("Summer 2026")
        val body = ContractSchemas.collectionCreateRequest.decode(fake.find(POST, Routes.collections()).first().json())
        assertEquals(id, body.id)
        assertEquals(WireCollectionKind.SHARED, body.kind)
        val wrapped = decodeBytes(body.keyByOwner)
        assertEquals(WRAPPED_BYTES, wrapped.size)
        val collectionKey = unwrapKey(sodium, harness.fixture.keys.masterKey, wrapped, AdContext.COLLECTION_KEY_BY_MASTER, id)
        val metadata = openCollectionName(collectionKey, id, decodeBytes(body.metadataBlob))
        assertEquals("Summer 2026", metadata.name)
        assertEquals(FIRST_NOW_ISO, metadata.createdAtUtc)
        val row = harness.collectionRow(id)
        assertNotNull(row)
        assertEquals(CollectionKind.SHARED, row!!.kind)
        assertEquals(CollectionRole.OWNER, row.role)
        assertEquals(0L, row.cursorSeq)
        assertEquals(0L, row.serverLastSeq)
        assertEquals(SERVER_OWNER_ID, row.ownerId)
        assertEquals(FIXTURE_USERNAME, row.ownerUsername)
        assertEquals(NOW, row.metaUpdatedAt)
        assertArrayEquals(wrapped, row.keyMaterial)
        assertArrayEquals(decodeBytes(body.metadataBlob), row.metadataBlob)
        assertEquals(listOf(emptyList<String>()), changed.toList())
    }

    @Test
    fun shareSealsTheCollectionKeySoTheRecipientOpensItWithOpenSharePayload() = sharingTest {
        val id = journal()
        val recipient = sodium.boxKeypair()
        fake.on(GET, Routes.userPublicKey("bea")) { publicKeyJson(RECIPIENT_ID, "bea", recipient.publicKey) }
        fake.on(POST, Routes.collectionShares(id)) { emptyObject() }
        val found: Recipient = service.lookupRecipient("Bea")
        assertEquals(publicKeyFingerprint(sodium, recipient.publicKey), found.fingerprint)
        service.share(id, found)
        val body = ContractSchemas.shareCreateRequest.decode(fake.find(POST, Routes.collectionShares(id)).first().json())
        assertEquals(RECIPIENT_ID, body.recipientUserId)
        val opened = openSharePayload(sodium, decodeBytes(body.sealedCollectionKey), recipient.publicKey, recipient.privateKey, id)
        assertArrayEquals(harness.entryKeys.collectionKey(id), opened)
    }

    @Test
    fun shareMaps409AlreadySharedAndAMissingUserToSharingErrors() = sharingTest {
        val id = journal()
        val recipient = sodium.boxKeypair()
        fake.on(GET, Routes.userPublicKey("bea")) { publicKeyJson(RECIPIENT_ID, "bea", recipient.publicKey) }
        fake.on(POST, Routes.collectionShares(id)) { throw apiFailure(409, ErrorCode.ALREADY_SHARED) }
        val found: Recipient = service.lookupRecipient("bea")
        assertEquals(SharingErrorReason.ALREADY_SHARED, expectFailure<SharingException> { service.share(id, found) }.reason)
        assertEquals(SharingErrorReason.USER_NOT_FOUND, expectFailure<SharingException> { service.lookupRecipient("nobody") }.reason)
    }

    @Test
    fun addEntriesSkipsUnsyncedEntriesWrapsTheEntryKeyWithTheCollectionKeyAndReportsAlreadyIn() = sharingTest {
        val id = journal()
        val synced = syncedEntry(1)
        val pending = harness.createEntry(listOf(fakeJpeg(1000, 2)))
        val conflicted = syncedEntry(3)
        fake.on(POST, Routes.collectionEntries(id)) { request: RecordedCall ->
            if (request.string("entryId") == conflicted) throw apiFailure(409, ErrorCode.ID_CONFLICT)
            emptyObject()
        }
        val result: AddEntriesResult = service.addEntries(id, listOf(synced, pending, conflicted, "missing-entry-id-00000"))
        assertEquals(
            AddEntriesResult(added = listOf(synced), alreadyIn = listOf(conflicted), notSynced = listOf(pending), failed = listOf("missing-entry-id-00000")),
            result,
        )
        val body = fake.find(POST, Routes.collectionEntries(id)).map { ContractSchemas.collectionEntryAddRequest.decode(it.json()) }.first { it.entryId == synced }
        val entryKey = unwrapKey(
            sodium,
            harness.entryKeys.collectionKey(id),
            decodeBytes(body.entryKeyByCollection),
            AdContext.ENTRY_KEY_BY_COLLECTION,
            "$id/$synced",
        )
        assertArrayEquals(harness.entryKeys.entryKey(synced), entryKey)
        val membership = harness.fixture.rowStore.transaction { it.memberships.get(id, synced) }
        assertNotNull(membership)
        assertArrayEquals(decodeBytes(body.entryKeyByCollection), membership!!.entryKeyWrap)
        assertEquals(0L, membership.seq)
        val posts = fake.count(POST, Routes.collectionEntries(id))
        val again: AddEntriesResult = service.addEntries(id, listOf(synced))
        assertEquals(listOf(synced), again.alreadyIn)
        assertEquals(posts, fake.count(POST, Routes.collectionEntries(id)))
        assertTrue(changed.flatten().contains(synced))
    }

    @Test
    fun removeEntriesAndDeleteJournalDropTheLocalMembershipsButKeepTheEntries() = sharingTest {
        val id = journal()
        val entryId = syncedEntry(4)
        fake.on(POST, Routes.collectionEntries(id)) { emptyObject() }
        fake.on(DELETE, Routes.collectionEntry(id, entryId)) { null }
        fake.on(DELETE, Routes.collection(id)) { null }
        service.addEntries(id, listOf(entryId))
        service.removeEntries(id, listOf(entryId))
        assertEquals(emptyList<MembershipRow>(), harness.fixture.rowStore.transaction { it.memberships.byCollection(id) })
        service.addEntries(id, listOf(entryId))
        service.deleteJournal(id)
        assertNull(harness.collectionRow(id))
        assertEquals(emptyList<MembershipRow>(), harness.fixture.rowStore.transaction { it.memberships.byCollection(id) })
        assertNotNull(harness.entryRow(entryId))
    }

    @Test
    fun leaveDropsEntriesReachableOnlyThroughTheJournalAndKeepsOwnEntries() = sharingTest {
        val id = journal()
        val onlyShared = syncedEntry(5)
        val ownToo = syncedEntry(6)
        val rowStore = harness.fixture.rowStore
        rowStore.transaction { tx ->
            val collection = tx.collections.get(id) ?: error("missing")
            tx.collections.put(collection.copy(role = CollectionRole.VIEWER, ownerId = OTHER_OWNER_ID))
            for (entryId in listOf(onlyShared, ownToo)) tx.memberships.put(MembershipRow(id, entryId, byteArrayOf(1), 1))
            tx.memberships.delete(harness.fixture.homeCollectionId, onlyShared)
        }
        fake.on(DELETE, Routes.collectionShare(id, harness.fixture.userId)) { null }
        service.leave(id)
        assertNull(harness.collectionRow(id))
        assertNull(harness.entryRow(onlyShared))
        assertNotNull(harness.entryRow(ownToo))
    }

    @Test
    fun surfacesOfflineAndDisabledStates() = sharingTest {
        fake.on(POST, Routes.collections()) { throw NetworkError() }
        assertEquals(SharingErrorReason.OFFLINE, expectFailure<SharingException> { journal() }.reason)
        sharingEnabled = false
        expectFailure<SharingException> { journal() }
        assertEquals(SharingErrorReason.DISABLED, expectFailure<SharingException> { journal() }.reason)
    }

    @Test
    fun myFingerprintIsTheFingerprintOfTheAccountPublicKey() = sharingTest {
        assertEquals(publicKeyFingerprint(sodium, harness.fixture.keys.publicKey), service.myFingerprint())
    }
}

@RunWith(RobolectricTestRunner::class)
class CollectionNamesTest : SharingTestBase() {
    @Test
    fun opensTheNameFallsBackForUnreadableMetadataAndForgetsOnClear() = sharingTest {
        val id = journal("Trip")
        val names: CollectionNames = DefaultCollectionNames(
            CollectionNamesDeps(sodium = sodium, rowStore = harness.fixture.rowStore, entryKeys = harness.entryKeys, ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
        )
        assertEquals(CollectionName.Named("Trip"), names.get(id))
        val key = harness.entryKeys.collectionKey(id)
        val renamed = sealCollectionName(key, id, "Renamed", NOW)
        harness.fixture.rowStore.transaction { tx -> tx.collections.get(id)?.let { tx.collections.put(it.copy(metadataBlob = renamed)) } }
        assertEquals(CollectionName.Named("Trip"), names.get(id))
        names.clear()
        assertEquals(CollectionName.Named("Renamed"), names.get(id))
        harness.fixture.rowStore.transaction { tx -> tx.collections.get(id)?.let { tx.collections.put(it.copy(metadataBlob = byteArrayOf(1, 2, 3))) } }
        names.clear()
        assertEquals(CollectionName.Unnamed, names.get(id))
    }
}
