@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.DiffMembership
import dev.kodakiko.contract.DiffPage
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.MediaDto
import dev.kodakiko.contract.ObjectDto
import dev.kodakiko.contract.ObjectState
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SyncCollectionState
import dev.kodakiko.contract.SyncStateResponse
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.STREAM_CHUNK_SIZE_EXPONENT
import dev.kodakiko.crypto.streamLayout
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.EntryLocalState
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.NewOutboxRow
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.ApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import dev.kodakiko.contract.CollectionKind as WireCollectionKind
import dev.kodakiko.contract.CollectionRole as WireCollectionRole
import dev.kodakiko.contract.ObjectVariant as WireObjectVariant

private const val LATER = "2026-11-13T10:00:00.000Z"

private class LoggedLine(val event: String, val fields: Map<String, Any?>)

private class PulledRows(
    val collections: List<CollectionRow>,
    val entries: List<EntryRow>,
    val memberships: List<MembershipRow>,
    val media: List<MediaRow>,
    val objects: List<ObjectRow>,
)

@RunWith(RobolectricTestRunner::class)
class PullTest : SyncTestBase() {
    private lateinit var fixture: AccountFixture
    private lateinit var fakeHttp: FakeHttp
    private lateinit var ioDispatcherScope: TestScope
    private val changed: MutableList<List<String>> = CopyOnWriteArrayList()
    private val logged: MutableList<LoggedLine> = CopyOnWriteArrayList()

    private fun TestScope.setUp() {
        ioDispatcherScope = this
        fixture = createAccountFixture()
        fixture.rowStore.transaction { tx -> tx.collections.delete(fixture.homeCollectionId) }
        fakeHttp = FakeHttp()
    }

    private fun pullTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            setUp()
            body()
        }

    private fun puller(): Pull =
        DefaultPull(
            PullDeps(
                http = fakeHttp,
                rowStore = fixture.rowStore,
                files = fixture.files,
                onEntriesChanged = { ids: List<String> -> changed += ids.toList() },
                log = SyncLog { event: String, fields: Map<String, Any?> -> logged += LoggedLine(event, fields.toMap()) },
                ioDispatcher = StandardTestDispatcher(ioDispatcherScope.testScheduler),
            ),
        )

    private fun rows(): PulledRows =
        fixture.rowStore.transaction { tx ->
            PulledRows(tx.collections.all(), tx.entries.all(), tx.memberships.all(), tx.media.all(), tx.objects.all())
        }

    private fun stateOf(
        collectionId: String,
        lastSeq: Long = 1,
        tombstonesPrunedThroughSeq: Long = 0,
        metaUpdatedAt: String = NOW_ISO,
        kind: WireCollectionKind = WireCollectionKind.HOME,
    ): JsonElement =
        ContractSchemas.syncStateResponse.encode(
            SyncStateResponse(listOf(SyncCollectionState(collectionId, kind, WireCollectionRole.OWNER, lastSeq, tombstonesPrunedThroughSeq, metaUpdatedAt))),
        )

    private fun mediaDtoFor(id: String, entryId: String): MediaDto = MediaDto(id, entryId, encodeBytes(byteArrayOf(7, 7)), NOW_ISO)

    private fun objectDtoFor(id: String, mediaId: String, variant: WireObjectVariant, plaintext: Long): ObjectDto =
        ObjectDto(id, mediaId, variant, streamLayout(plaintext, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize, ObjectState.UPLOADED)

    private fun membershipOf(entryId: String, seq: Long, removed: Boolean = false): DiffMembership =
        DiffMembership(entryId, if (removed) null else encodeBytes(byteArrayOf(9, 9, 9)), seq, removed)

    private fun page(
        collectionId: String,
        memberships: List<DiffMembership> = emptyList(),
        entries: List<JsonElement> = emptyList(),
        media: List<MediaDto> = emptyList(),
        objects: List<ObjectDto> = emptyList(),
        nextCursor: Long = 1,
        hasMore: Boolean = false,
        resyncRequired: Boolean = false,
    ): JsonElement =
        ContractSchemas.diffPage.encode(
            DiffPage(
                collectionId = collectionId,
                memberships = memberships,
                entries = entries.map { ContractSchemas.entryDto.decode(it) },
                media = media,
                objects = objects,
                nextCursor = nextCursor,
                hasMore = hasMore,
                resyncRequired = resyncRequired,
            ),
        )

    private fun diffPath(collectionId: String, since: Long): String = "${Routes.collectionDiff(collectionId)}?since=$since&limit=500"

    private fun storedHomeId(): JsonElement? = fixture.rowStore.kvGetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID)

    @Test
    fun firstSyncRefetchesTheCollectionAndAppliesTheFirstDiffPageInOneGo() = pullTest {
        val collectionId = fixture.homeCollectionId
        val entryId = fixture.randomId()
        val mediaId = fixture.randomId()
        val originalId = fixture.randomId()
        val thumbnailId = fixture.randomId()
        val blob = byteArrayOf(1, 1, 1)
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId) }
        fakeHttp.on(GET, Routes.collection(collectionId)) { collectionDtoJson(collectionId) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) {
            page(
                collectionId,
                memberships = listOf(membershipOf(entryId, 1)),
                entries = listOf(entryDtoJson(entryId, 1, blob)),
                media = listOf(mediaDtoFor(mediaId, entryId)),
                objects = listOf(
                    objectDtoFor(originalId, mediaId, WireObjectVariant.ORIGINAL, 1000),
                    objectDtoFor(thumbnailId, mediaId, WireObjectVariant.THUMBNAIL, 64),
                ),
                nextCursor = 1,
            )
        }
        val result = puller().pull()
        assertEquals(1, result.pulledEntries)
        val state = rows()
        val collection = state.collections[0]
        assertEquals(collectionId, collection.id)
        assertEquals(CollectionKind.HOME, collection.kind)
        assertEquals(CollectionRole.OWNER, collection.role)
        assertEquals(FIXTURE_USERNAME, collection.ownerUsername)
        assertEquals(NOW_ISO, collection.metaUpdatedAt)
        assertEquals(1L, collection.cursorSeq)
        val entry = state.entries[0]
        assertEquals(entryId, entry.id)
        assertEquals(1L, entry.revision)
        assertEquals(1L, entry.baseRevision)
        assertEquals(EntryLocalState.SYNCED, entry.localState)
        assertNull(entry.trashedAt)
        assertArrayEquals(blob, entry.baseMetadataBlob)
        assertEquals(listOf(Triple(collectionId, entryId, 1L)), state.memberships.map { Triple(it.collectionId, it.entryId, it.seq) })
        assertEquals(listOf(mediaId), state.media.map { it.id })
        assertEquals(
            setOf(
                Triple(originalId, ObjectUploadState.UPLOADED, ObjectLocalState.MISSING),
                Triple(thumbnailId, ObjectUploadState.UPLOADED, ObjectLocalState.MISSING),
            ),
            state.objects.map { Triple(it.id, it.uploadState, it.localState) }.toSet(),
        )
        assertEquals(2, state.objects.size)
        assertEquals(1000L, state.objects.first { it.id == originalId }.plaintextSize)
        assertTrue(changed.flatten().contains(entryId))
    }

    @Test
    fun firstSyncDoesNotAskForDiffsWhenEveryCollectionIsCurrent() = pullTest {
        val collectionId = fixture.homeCollectionId
        fixture.rowStore.transaction { tx -> tx.collections.put(collectionRow(collectionId, serverLastSeq = 4, cursorSeq = 4)) }
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 4) }
        val result = puller().pull()
        assertEquals(0, result.pulledEntries)
        assertEquals(1, fakeHttp.requests.size)
    }

    @Test
    fun firstSyncIgnoresLinkCollections() = pullTest {
        fakeHttp.on(GET, Routes.syncState()) { stateOf(fixture.randomId(), kind = WireCollectionKind.LINK, lastSeq = 5) }
        puller().pull()
        assertEquals(1, fakeHttp.requests.size)
        assertEquals(emptyList<CollectionRow>(), rows().collections)
    }

    @Test
    fun homeCollectionIdIsRecordedForTheOwnedHomeCollectionWhenTheKeyIsMissing() = pullTest {
        val collectionId = fixture.homeCollectionId
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId) }
        fakeHttp.on(GET, Routes.collection(collectionId)) { collectionDtoJson(collectionId) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) { page(collectionId) }
        assertNull(storedHomeId())
        puller().pull()
        assertEquals(JsonPrimitive(collectionId), storedHomeId())
    }

    @Test
    fun homeCollectionIdKeepsAnExistingHomeCollectionId() = pullTest {
        val collectionId = fixture.homeCollectionId
        val existing = fixture.randomId()
        fixture.rowStore.kvSetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID, JsonPrimitive(existing))
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId) }
        fakeHttp.on(GET, Routes.collection(collectionId)) { collectionDtoJson(collectionId) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) { page(collectionId) }
        puller().pull()
        assertEquals(JsonPrimitive(existing), storedHomeId())
    }

    @Test
    fun homeCollectionIdDoesNotRecordASharedCollection() = pullTest {
        val collectionId = fixture.randomId()
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, kind = WireCollectionKind.SHARED) }
        fakeHttp.on(GET, Routes.collection(collectionId)) {
            collectionDtoJson(collectionId, kind = WireCollectionKind.SHARED, role = WireCollectionRole.VIEWER)
        }
        fakeHttp.on(GET, diffPath(collectionId, 0)) { page(collectionId) }
        puller().pull()
        assertNull(storedHomeId())
    }

    @Test
    fun collectionsRefetchesACollectionWhoseMetaUpdatedAtChanged() = pullTest {
        val collectionId = fixture.homeCollectionId
        fixture.rowStore.transaction { tx -> tx.collections.put(collectionRow(collectionId, serverLastSeq = 0, cursorSeq = 0)) }
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 0, metaUpdatedAt = LATER) }
        fakeHttp.on(GET, Routes.collection(collectionId)) { collectionDtoJson(collectionId, metaUpdatedAt = LATER) }
        puller().pull()
        val row = rows().collections.single()
        assertEquals(LATER, row.metaUpdatedAt)
        assertArrayEquals(byteArrayOf(1, 2, 3), row.keyMaterial)
    }

    @Test
    fun collectionsDropsACollectionThatDisappearedRemotelyWithItsOrphanedEntriesAndFiles() = pullTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        fixture.rowStore.transaction { tx -> tx.collections.put(collectionRow(fixture.homeCollectionId, serverLastSeq = 1, cursorSeq = 1)) }
        fakeHttp.on(GET, Routes.syncState()) { ContractSchemas.syncStateResponse.encode(SyncStateResponse(emptyList())) }
        puller().pull()
        val state = rows()
        assertEquals(emptyList<CollectionRow>(), state.collections)
        assertEquals(emptyList<EntryRow>(), state.entries)
        assertEquals(emptyList<ObjectRow>(), state.objects)
        assertNull(fixture.fileStore.size(local.originalObjectIds[0]))
        assertTrue(changed.flatten().contains(local.entryId))
    }

    private fun seedSyncedEntry(revision: Long, blob: ByteArray, localState: EntryLocalState = EntryLocalState.SYNCED): LocalEntry {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        fixture.rowStore.transaction { tx ->
            tx.entries.get(local.entryId)?.let {
                tx.entries.put(it.copy(revision = revision, baseRevision = revision, metadataBlob = blob, baseMetadataBlob = blob, localState = localState))
            }
            tx.objects.all().forEach { tx.objects.put(it.copy(uploadState = ObjectUploadState.UPLOADED)) }
            tx.collections.put(collectionRow(fixture.homeCollectionId, serverLastSeq = 1, cursorSeq = 1))
        }
        return local
    }

    private fun serveDiff(since: Long, response: JsonElement, lastSeq: Long = 5) {
        fakeHttp.on(GET, Routes.syncState()) { stateOf(fixture.homeCollectionId, lastSeq = lastSeq) }
        fakeHttp.on(GET, diffPath(fixture.homeCollectionId, since)) { response }
    }

    @Test
    fun applyingPagesOverwritesASyncedEntryWithANewerRevisionAndAlwaysTakesTrashedAt() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1))
        val newer = byteArrayOf(2, 2)
        serveDiff(
            1,
            page(
                fixture.homeCollectionId,
                memberships = listOf(membershipOf(local.entryId, 2)),
                entries = listOf(entryDtoJson(local.entryId, 2, newer, LATER)),
                media = local.mediaIds.map { mediaDtoFor(it, local.entryId) },
                objects = local.originalObjectIds.map { objectDtoFor(it, local.mediaIds[0], WireObjectVariant.ORIGINAL, 1) } +
                    local.thumbnailObjectIds.map { objectDtoFor(it, local.mediaIds[0], WireObjectVariant.THUMBNAIL, 64) },
                nextCursor = 2,
            ),
        )
        puller().pull()
        val entry = rows().entries.single()
        assertArrayEquals(newer, entry.metadataBlob)
        assertEquals(2L, entry.revision)
        assertEquals(2L, entry.baseRevision)
        assertEquals(LATER, entry.trashedAt)
        assertEquals(EntryLocalState.SYNCED, entry.localState)
    }

    @Test
    fun applyingPagesKeepsAPendingLocalChangeAndStoresThePulledCopyAsTheServerPendingBlob() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1), EntryLocalState.PENDING_UPDATE)
        val mine = byteArrayOf(5, 5)
        fixture.updateEntry(local.entryId) { it.copy(metadataBlob = mine) }
        val theirs = byteArrayOf(3, 3)
        serveDiff(
            1,
            page(
                fixture.homeCollectionId,
                memberships = listOf(membershipOf(local.entryId, 2)),
                entries = listOf(entryDtoJson(local.entryId, 2, theirs, LATER)),
                media = local.mediaIds.map { mediaDtoFor(it, local.entryId) },
                nextCursor = 2,
            ),
        )
        puller().pull()
        val entry = rows().entries.single()
        assertArrayEquals(mine, entry.metadataBlob)
        assertArrayEquals(theirs, entry.serverPendingBlob)
        assertEquals(2L, entry.serverPendingRevision)
        assertEquals(LATER, entry.trashedAt)
        assertEquals(EntryLocalState.PENDING_UPDATE, entry.localState)
        assertEquals(1L, entry.baseRevision)
    }

    @Test
    fun applyingPagesRejectsARollbackLogsItAndKeepsTheNewerLocalBlob() = pullTest {
        val local = seedSyncedEntry(5, byteArrayOf(5))
        serveDiff(
            1,
            page(
                fixture.homeCollectionId,
                memberships = listOf(membershipOf(local.entryId, 2)),
                entries = listOf(entryDtoJson(local.entryId, 3, byteArrayOf(3))),
                media = local.mediaIds.map { mediaDtoFor(it, local.entryId) },
                nextCursor = 2,
            ),
        )
        puller().pull()
        val entry = rows().entries.single()
        assertArrayEquals(byteArrayOf(5), entry.metadataBlob)
        assertEquals(5L, entry.revision)
        assertEquals(listOf(SyncLogEvents.ROLLBACK_REJECTED), logged.map { it.event })
        val fields = logged[0].fields
        assertEquals(local.entryId, fields["entryId"])
        assertEquals(3L, fields["pulledRevision"])
        assertEquals(5L, fields["baseRevision"])
    }

    @Test
    fun applyingPagesRemovesAnEntryWhoseLastMembershipIsRemovedWithMediaObjectsAndFiles() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1))
        serveDiff(1, page(fixture.homeCollectionId, memberships = listOf(membershipOf(local.entryId, 2, removed = true)), nextCursor = 2))
        puller().pull()
        val state = rows()
        assertEquals(emptyList<EntryRow>(), state.entries)
        assertEquals(emptyList<MediaRow>(), state.media)
        assertEquals(emptyList<ObjectRow>(), state.objects)
        assertEquals(emptyList<MembershipRow>(), state.memberships)
        assertNull(fixture.fileStore.size(local.originalObjectIds[0]))
    }

    @Test
    fun applyingPagesKeepsAnEntryThatStillHasAnotherMembership() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1))
        val otherCollection = fixture.randomId()
        fixture.rowStore.transaction { tx -> tx.memberships.put(MembershipRow(otherCollection, local.entryId, byteArrayOf(1), 1)) }
        serveDiff(1, page(fixture.homeCollectionId, memberships = listOf(membershipOf(local.entryId, 2, removed = true)), nextCursor = 2))
        puller().pull()
        assertEquals(1, rows().entries.size)
    }

    @Test
    fun applyingPagesDeletesAnOlderObjectRowForTheSameMediaVariantWithItsFile() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1))
        val mediaId = local.mediaIds[0]
        val replacement = fixture.randomId()
        serveDiff(
            1,
            page(
                fixture.homeCollectionId,
                memberships = listOf(membershipOf(local.entryId, 2)),
                entries = listOf(entryDtoJson(local.entryId, 1, byteArrayOf(1))),
                media = listOf(mediaDtoFor(mediaId, local.entryId)),
                objects = listOf(objectDtoFor(replacement, mediaId, WireObjectVariant.ORIGINAL, 1)) +
                    local.thumbnailObjectIds.map { objectDtoFor(it, mediaId, WireObjectVariant.THUMBNAIL, 64) },
                nextCursor = 2,
            ),
        )
        puller().pull()
        val ids = rows().objects.map { it.id }
        assertTrue(ids.contains(replacement))
        assertFalse(ids.contains(local.originalObjectIds[0]))
        assertNull(fixture.fileStore.size(local.originalObjectIds[0]))
    }

    @Test
    fun applyingPagesDeletesLocalMediaTheServerNoLongerListsWhenAllItsObjectsWereUploaded() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1))
        serveDiff(
            1,
            page(
                fixture.homeCollectionId,
                memberships = listOf(membershipOf(local.entryId, 2)),
                entries = listOf(entryDtoJson(local.entryId, 2, byteArrayOf(2))),
                nextCursor = 2,
            ),
        )
        puller().pull()
        val state = rows()
        assertEquals(emptyList<MediaRow>(), state.media)
        assertEquals(emptyList<ObjectRow>(), state.objects)
    }

    @Test
    fun applyingPagesKeepsLocalMediaWhoseObjectsAreNotUploadedYet() = pullTest {
        val local = seedSyncedEntry(1, byteArrayOf(1))
        fixture.setObjectStates(ObjectUploadState.LOCAL)
        serveDiff(
            1,
            page(
                fixture.homeCollectionId,
                memberships = listOf(membershipOf(local.entryId, 2)),
                entries = listOf(entryDtoJson(local.entryId, 2, byteArrayOf(2))),
                nextCursor = 2,
            ),
        )
        puller().pull()
        assertEquals(1, rows().media.size)
    }

    @Test
    fun pagesCommitTheCursorPerPageAndContinueWhileHasMore() = pullTest {
        val collectionId = fixture.homeCollectionId
        val first = fixture.randomId()
        val second = fixture.randomId()
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 2) }
        fakeHttp.on(GET, Routes.collection(collectionId)) { collectionDtoJson(collectionId) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) {
            page(collectionId, memberships = listOf(membershipOf(first, 1)), entries = listOf(entryDtoJson(first, 1, byteArrayOf(1))), nextCursor = 1, hasMore = true)
        }
        fakeHttp.on(GET, diffPath(collectionId, 1)) { throw apiFailure(500, ErrorCode.INTERNAL) }
        val failure = expectFailure<ApiError> { puller().pull() }
        assertEquals(500, failure.status)
        assertEquals(1L, rows().collections[0].cursorSeq)
        fakeHttp.on(GET, diffPath(collectionId, 1)) {
            page(collectionId, memberships = listOf(membershipOf(second, 2)), entries = listOf(entryDtoJson(second, 1, byteArrayOf(2))), nextCursor = 2)
        }
        puller().pull()
        assertEquals(listOf(first, second).sorted(), rows().entries.map { it.id }.sorted())
        assertEquals(2L, rows().collections[0].cursorSeq)
    }

    @Test
    fun resyncWipesTheCollectionMembershipsRePullsFrom0AndRemovesEntriesTheServerForgot() = pullTest {
        val kept = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        val forgotten = createLocalEntry(fixture, listOf(byteArrayOf(2)), "", emptyList(), enqueue = false)
        val pending = createLocalEntry(fixture, listOf(byteArrayOf(3)), "", emptyList(), enqueue = false)
        fixture.rowStore.transaction { tx ->
            for (local in listOf(kept, forgotten)) {
                tx.entries.get(local.entryId)?.let {
                    tx.entries.put(it.copy(localState = EntryLocalState.SYNCED, revision = 1, baseRevision = 1, baseMetadataBlob = it.metadataBlob))
                }
            }
            tx.objects.all().forEach { tx.objects.put(it.copy(uploadState = ObjectUploadState.UPLOADED)) }
            tx.collections.put(collectionRow(fixture.homeCollectionId, serverLastSeq = 3, cursorSeq = 3))
        }
        val collectionId = fixture.homeCollectionId
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 20, tombstonesPrunedThroughSeq = 10) }
        val keptBlob = byteArrayOf(8)
        fakeHttp.on(GET, diffPath(collectionId, 0)) {
            page(
                collectionId,
                memberships = listOf(membershipOf(kept.entryId, 4)),
                entries = listOf(entryDtoJson(kept.entryId, 1, keptBlob)),
                media = kept.mediaIds.map { mediaDtoFor(it, kept.entryId) },
                objects = listOf(
                    objectDtoFor(kept.originalObjectIds[0], kept.mediaIds[0], WireObjectVariant.ORIGINAL, 1),
                    objectDtoFor(kept.thumbnailObjectIds[0], kept.mediaIds[0], WireObjectVariant.THUMBNAIL, 64),
                ),
                nextCursor = 20,
            )
        }
        puller().pull()
        val ids = rows().entries.map { it.id }
        assertTrue(ids.contains(kept.entryId))
        assertFalse(ids.contains(forgotten.entryId))
        assertTrue(ids.contains(pending.entryId))
        assertEquals(20L, rows().collections[0].cursorSeq)
        assertEquals(0, fakeHttp.count(GET, diffPath(collectionId, 3)))
    }

    @Test
    fun resyncRestartsFrom0WhenAPageReportsResyncRequired() = pullTest {
        val collectionId = fixture.homeCollectionId
        fixture.rowStore.transaction { tx -> tx.collections.put(collectionRow(collectionId, serverLastSeq = 5, cursorSeq = 5)) }
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 9) }
        fakeHttp.on(GET, diffPath(collectionId, 5)) { page(collectionId, resyncRequired = true, nextCursor = 0) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) { page(collectionId, nextCursor = 9) }
        puller().pull()
        assertEquals(1, fakeHttp.count(GET, diffPath(collectionId, 0)))
        assertEquals(9L, rows().collections[0].cursorSeq)
    }

    private class ResyncSeed(val kept: LocalEntry, val forgotten: LocalEntry, val updating: LocalEntry, val queued: LocalEntry)

    private fun seedResyncCollection(): ResyncSeed {
        val kept = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        val forgotten = createLocalEntry(fixture, listOf(byteArrayOf(2)), "", emptyList(), enqueue = false)
        val updating = createLocalEntry(fixture, listOf(byteArrayOf(3)), "", emptyList(), enqueue = false)
        val queued = createLocalEntry(fixture, listOf(byteArrayOf(4)), "", emptyList(), enqueue = false)
        fixture.rowStore.transaction { tx ->
            for (local in listOf(kept, forgotten, updating, queued)) {
                val entry = tx.entries.get(local.entryId) ?: continue
                val localState = if (local === updating) EntryLocalState.PENDING_UPDATE else EntryLocalState.SYNCED
                tx.entries.put(entry.copy(localState = localState, revision = 1, baseRevision = 1, baseMetadataBlob = entry.metadataBlob))
            }
            tx.outbox.enqueue(NewOutboxRow("trashEntry", entryPayload(queued.entryId), OutboxState.QUEUED, 0, 0, 0, null))
            tx.collections.put(collectionRow(fixture.homeCollectionId, serverLastSeq = 3, cursorSeq = 3))
        }
        return ResyncSeed(kept, forgotten, updating, queued)
    }

    @Test
    fun resyncDropsOrphanedEntriesInTheWipeTransactionItselfSoAnInterruptedResyncLeavesNoStaleEntries() = pullTest {
        val seed = seedResyncCollection()
        val collectionId = fixture.homeCollectionId
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 20, tombstonesPrunedThroughSeq = 10) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) { throw apiFailure(500, ErrorCode.INTERNAL) }
        val failure = expectFailure<ApiError> { puller().pull() }
        assertEquals(500, failure.status)
        val state = rows()
        val ids = state.entries.map { it.id }
        assertFalse(ids.contains(seed.kept.entryId))
        assertFalse(ids.contains(seed.forgotten.entryId))
        assertTrue(ids.contains(seed.updating.entryId))
        assertTrue(ids.contains(seed.queued.entryId))
        assertEquals(0L, state.collections[0].cursorSeq)
        assertNull(fixture.fileStore.size(seed.forgotten.originalObjectIds[0]))
        assertTrue(changed.flatten().contains(seed.forgotten.entryId))
    }

    @Test
    fun resyncKeepsAPendingEntryThatTheServerStillListsAfterTheResync() = pullTest {
        val seed = seedResyncCollection()
        val collectionId = fixture.homeCollectionId
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 20, tombstonesPrunedThroughSeq = 10) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) {
            page(
                collectionId,
                memberships = listOf(membershipOf(seed.updating.entryId, 4)),
                entries = listOf(entryDtoJson(seed.updating.entryId, 2, byteArrayOf(9))),
                media = seed.updating.mediaIds.map { mediaDtoFor(it, seed.updating.entryId) },
                nextCursor = 20,
            )
        }
        puller().pull()
        val entry = rows().entries.first { it.id == seed.updating.entryId }
        assertEquals(EntryLocalState.PENDING_UPDATE, entry.localState)
        assertEquals(2L, entry.serverPendingRevision)
    }

    @Test
    fun resyncThrowsWhenTheServerKeepsDemandingAResyncInsteadOfSilentlyGivingUp() = pullTest {
        val collectionId = fixture.homeCollectionId
        fixture.rowStore.transaction { tx -> tx.collections.put(collectionRow(collectionId, serverLastSeq = 5, cursorSeq = 5)) }
        fakeHttp.on(GET, Routes.syncState()) { stateOf(collectionId, lastSeq = 9) }
        fakeHttp.on(GET, diffPath(collectionId, 5)) { page(collectionId, resyncRequired = true, nextCursor = 0) }
        fakeHttp.on(GET, diffPath(collectionId, 0)) { page(collectionId, resyncRequired = true, nextCursor = 0) }
        expectFailure<Exception> { puller().pull() }
    }
}
