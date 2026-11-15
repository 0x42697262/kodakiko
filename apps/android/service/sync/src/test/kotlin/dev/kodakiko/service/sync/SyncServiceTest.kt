@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.NewOutboxRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.entries.AccountLockedException
import dev.kodakiko.service.entries.EntriesSyncPort
import dev.kodakiko.sync.MAX_ATTEMPTS
import dev.kodakiko.sync.PUSH_CONCURRENCY
import dev.kodakiko.sync.PushFailure
import dev.kodakiko.sync.SYNC_DEBOUNCE_MS
import dev.kodakiko.sync.SYNC_INTERVAL_MS
import dev.kodakiko.sync.nextAttemptAt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class FakeLifecycle : SyncLifecycle {
    @Volatile
    private var activeListener: ((Boolean) -> Unit)? = null

    @Volatile
    private var onlineListener: (() -> Unit)? = null

    @Volatile
    var activeUnsubscribed: Boolean = false

    override fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit {
        activeListener = listener
        return { activeUnsubscribed = true }
    }

    override fun subscribeOnline(listener: () -> Unit): () -> Unit {
        onlineListener = listener
        return { onlineListener = null }
    }

    fun fireActive(active: Boolean) {
        activeListener?.invoke(active)
    }

    fun fireOnline() {
        onlineListener?.invoke()
    }
}

private class FakeUploadCoordinator : UploadCoordinator {
    val uploadedListeners: MutableList<(String) -> Unit> = CopyOnWriteArrayList()
    val handledEvents: MutableList<UploadEvent> = CopyOnWriteArrayList()

    @Volatile
    var heldBack: Int = 0

    override suspend fun enqueueObjectUploads(objectIds: List<String>) = Unit

    override suspend fun reconcileUploads() = Unit

    override suspend fun handleEvent(event: UploadEvent) {
        handledEvents += event
    }

    override fun heldBackCount(): Int = heldBack

    override fun onObjectUploaded(listener: (String) -> Unit): () -> Unit {
        uploadedListeners += listener
        return {}
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncServiceTest : SyncTestBase() {
    private val keys = AccountKeys(masterKey = ByteArray(32), privateKey = ByteArray(32), publicKey = ByteArray(32))
    private lateinit var fixture: AccountFixture
    private lateinit var service: SyncService
    private lateinit var uploader: FakeUploader
    private lateinit var uploads: FakeUploadCoordinator
    private lateinit var lifecycle: FakeLifecycle
    private lateinit var accountState: Observable<AccountState>
    private val clock = TestSyncClock(1_000_000)
    private val calls: MutableList<Long> = CopyOnWriteArrayList()
    private val pullResults = ArrayDeque<Result<PullResult>>()
    private val pullCalls = AtomicInteger(0)
    private val invalid = AtomicInteger(0)

    @Volatile
    private var handler: suspend (OutboxRow) -> ExecutorResult = { ExecutorResult.Done }

    @Volatile
    private var localOnly: Boolean = false

    private var now: Long
        get() = clock.nowMs
        set(value) {
            clock.nowMs = value
        }

    private fun row(
        kind: OutboxKind,
        entryId: String,
        state: OutboxState = OutboxState.QUEUED,
        attempts: Long = 0,
        nextAttemptAt: Long = 0,
        lastErrorCode: String? = null,
    ): NewOutboxRow = NewOutboxRow(kind.wire, entryPayload(entryId), state, 0, attempts, nextAttemptAt, lastErrorCode)

    private fun enqueue(vararg rows: NewOutboxRow): List<Long> = fixture.rowStore.transaction { tx -> rows.map { tx.outbox.enqueue(it) } }

    private fun outbox(): List<OutboxRow> = fixture.outbox()

    private fun executorMap(): Map<OutboxKind, PushExecutor> =
        OutboxKind.entries.associateWith {
            PushExecutor { item: OutboxRow, _: ExecutorDeps ->
                calls += item.id
                handler(item)
            }
        }

    private fun TestScope.setUp() {
        fixture = createAccountFixture()
        uploader = FakeUploader()
        uploads = FakeUploadCoordinator()
        lifecycle = FakeLifecycle()
        accountState = Observable(AccountState.Unlocked(keys, "u", "n"))
        service = DefaultSyncService(
            SyncServiceDeps(
                http = FakeHttp(),
                rowStore = fixture.rowStore,
                files = fixture.files,
                uploader = uploader,
                uploads = uploads,
                pull = Pull {
                    pullCalls.incrementAndGet()
                    (pullResults.removeFirstOrNull() ?: Result.success(PullResult(0))).getOrThrow()
                },
                conflict = ConflictResolver { _: String, _: ServerEntryVersion -> ConflictOutcome.ADOPTED },
                executors = executorMap(),
                onEntriesChanged = {},
                clock = clock,
                random = { 0.5 },
                lifecycle = lifecycle,
                accountState = accountState,
                onSessionInvalid = { invalid.incrementAndGet() },
                log = noLog(),
                localOnly = { localOnly },
                scope = backgroundScope,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ),
        )
    }

    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            setUp()
            try {
                body()
            } finally {
                service.stop()
            }
        }

    private fun httpFailure(status: Int, code: ErrorCode, retryAfterSeconds: Double? = null): ExecutorResult =
        ExecutorResult.Failed(PushFailure.Http(status, code, retryAfterSeconds))

    @Test
    fun localOnlyModeAPassPushesAndPullsNothingAndKeepsTheOutbox() = syncTest {
        localOnly = true
        enqueue(row(OutboxKind.TRASH_ENTRY, fixture.randomId()))
        val report = service.syncNow()
        assertEquals(0, report.pushed)
        assertEquals(emptyList<Long>(), calls.toList())
        assertEquals(0, pullCalls.get())
        assertEquals(1, outbox().size)
    }

    @Test
    fun runsDueItemsInIdOrderAndDeletesFinishedOnes() = syncTest {
        val entries = listOf(fixture.randomId(), fixture.randomId(), fixture.randomId())
        val ids = enqueue(*entries.map { row(OutboxKind.TRASH_ENTRY, it) }.toTypedArray())
        val report = service.syncNow()
        assertEquals(ids, calls.toList())
        assertEquals(emptyList<OutboxRow>(), outbox())
        assertEquals(3, report.pushed)
        assertEquals(0, report.failed)
    }

    @Test
    fun runsSameEntryItemsStrictlyOneAfterAnother() = syncTest {
        val entryId = fixture.randomId()
        val (first, second) = enqueue(row(OutboxKind.CREATE_ENTRY, entryId), row(OutboxKind.UPDATE_ENTRY_META, entryId))
        var secondStarted = false
        var overlapped = false
        handler = { item ->
            if (item.id == first) yield()
            if (item.id == second) {
                secondStarted = true
            } else if (secondStarted) {
                overlapped = true
            }
            ExecutorResult.Done
        }
        service.syncNow()
        assertEquals(listOf(first, second), calls.toList())
        assertFalse(overlapped)
    }

    @Test
    fun reportsAnEntryAsInFlightOnlyWhileOneOfItsItemsRuns() = syncTest {
        val entryId = fixture.randomId()
        enqueue(row(OutboxKind.TRASH_ENTRY, entryId))
        val seen = CopyOnWriteArrayList<Boolean>()
        handler = {
            seen += service.isEntryInFlight(entryId)
            seen += service.isEntryInFlight(fixture.randomId())
            ExecutorResult.Done
        }
        assertFalse(service.isEntryInFlight(entryId))
        service.syncNow()
        assertEquals(listOf(true, false), seen.toList())
        assertFalse(service.isEntryInFlight(entryId))
    }

    @Test
    fun servesAsTheEntriesSyncPort() = syncTest {
        val port: EntriesSyncPort = service
        assertFalse(port.isEntryInFlight(fixture.randomId()))
    }

    @Test
    fun keepsLaterSameEntryItemsBlockedWhileTheEarlierOneFailsAndWaits() = syncTest {
        val entryId = fixture.randomId()
        enqueue(row(OutboxKind.CREATE_ENTRY, entryId), row(OutboxKind.UPDATE_ENTRY_META, entryId))
        handler = { ExecutorResult.Failed(PushFailure.Network) }
        service.syncNow()
        assertEquals(1, calls.size)
    }

    @Test
    fun neverRunsMoreThanPushConcurrencyItemsAtOnce() = syncTest {
        enqueue(*Array(9) { row(OutboxKind.TRASH_ENTRY, fixture.randomId()) })
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        handler = {
            peak.accumulateAndGet(active.incrementAndGet()) { left, right -> maxOf(left, right) }
            delay(3)
            active.decrementAndGet()
            ExecutorResult.Done
        }
        service.syncNow()
        assertEquals(PUSH_CONCURRENCY, peak.get())
        assertEquals(9, calls.size)
    }

    @Test
    fun keepsAWaitingItemQueuedWithoutCountingAnAttemptDueAfterSyncIntervalMs() = syncTest {
        val entryId = fixture.randomId()
        val (id) = enqueue(row(OutboxKind.CREATE_ENTRY, entryId))
        handler = { ExecutorResult.Waiting }
        service.syncNow()
        val item = outbox().single()
        assertEquals(id, item.id)
        assertEquals(0L, item.attempts)
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals(now + SYNC_INTERVAL_MS, item.nextAttemptAt)
        assertEquals(1, calls.size)
    }

    @Test
    fun makesAWaitingItemDueAtOnceWhenOneOfItsUploadsWasConfirmedMeanwhile() = syncTest {
        val entryId = fixture.randomId()
        enqueue(row(OutboxKind.CREATE_ENTRY, entryId))
        var kicked = false
        handler = {
            if (!kicked) uploads.uploadedListeners.forEach { listener -> listener(entryId) }
            kicked = true
            ExecutorResult.Waiting
        }
        service.syncNow()
        assertTrue(calls.size >= 2)
        assertEquals(now + SYNC_INTERVAL_MS, outbox().single().nextAttemptAt)
    }

    @Test
    fun retriesANetworkFailureWithBackoffCountsTheAttemptAndFlagsOffline() = syncTest {
        val (id) = enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()))
        handler = { ExecutorResult.Failed(PushFailure.Network) }
        pullResults.addLast(Result.failure(NetworkError()))
        val report = service.syncNow()
        val item = outbox().single()
        assertEquals(id, item.id)
        assertEquals(1L, item.attempts)
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals("NETWORK", item.lastErrorCode)
        assertEquals(nextAttemptAt(1, now.toDouble()) { 0.5 }.toLong(), item.nextAttemptAt)
        assertTrue(service.status().offline)
        assertEquals(2, report.failed)
    }

    @Test
    fun clearsOfflineAfterASuccess() = syncTest {
        enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()))
        handler = { ExecutorResult.Failed(PushFailure.Network) }
        service.syncNow()
        handler = { ExecutorResult.Done }
        now += 10_000_000
        service.syncNow()
        assertFalse(service.status().offline)
    }

    @Test
    fun movesAnItemToDeadOnceItFailsMaxAttemptsTimes() = syncTest {
        val (id) = enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId(), attempts = (MAX_ATTEMPTS - 1).toLong()))
        handler = { ExecutorResult.Failed(PushFailure.Network) }
        service.syncNow()
        val item = outbox().single()
        assertEquals(id, item.id)
        assertEquals(OutboxState.DEAD, item.state)
        assertEquals(1, service.status().dead)
    }

    @Test
    fun deadLettersAQuotaErrorAtOnceKeepsItsCodeAndKeepsDrainingOthers() = syncTest {
        val (bad) = enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()), row(OutboxKind.TRASH_ENTRY, fixture.randomId()))
        handler = { item -> if (item.id == bad) httpFailure(403, ErrorCode.QUOTA_EXCEEDED) else ExecutorResult.Done }
        service.syncNow()
        val items = outbox()
        assertEquals(1, items.size)
        assertEquals(bad, items[0].id)
        assertEquals(OutboxState.DEAD, items[0].state)
        assertEquals("QUOTA_EXCEEDED", items[0].lastErrorCode)
        assertEquals(1, service.status().dead)
        assertEquals(0, service.status().queued)
    }

    @Test
    fun honorsRetryAfterOnA429() = syncTest {
        enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()))
        handler = { httpFailure(429, ErrorCode.RATE_LIMITED, 30.0) }
        service.syncNow()
        assertEquals(now + 30_000, outbox()[0].nextAttemptAt)
    }

    @Test
    fun dropsA404OnDeleteEntryAndRemovesTheEntryLocally() = syncTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        enqueue(row(OutboxKind.DELETE_ENTRY, local.entryId))
        handler = { httpFailure(404, ErrorCode.NOT_FOUND) }
        service.syncNow()
        assertEquals(emptyList<OutboxRow>(), outbox())
        assertNull(fixture.entry(local.entryId))
        assertNull(fixture.fileStore.size(local.originalObjectIds[0]))
    }

    @Test
    fun retriesWithBackoffWhenTheExecutorCouldNotResolveARevisionConflict() = syncTest {
        enqueue(row(OutboxKind.UPDATE_ENTRY_META, fixture.randomId()))
        handler = { httpFailure(409, ErrorCode.REVISION_CONFLICT) }
        service.syncNow()
        val item = outbox().single()
        assertEquals(1L, item.attempts)
        assertEquals(OutboxState.QUEUED, item.state)
        assertTrue(item.nextAttemptAt > now - 1)
    }

    @Test
    fun dropsA404OnAnUpdateAndRemovesTheEntryLocally() = syncTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        enqueue(row(OutboxKind.UPDATE_ENTRY_META, local.entryId))
        handler = { httpFailure(404, ErrorCode.NOT_FOUND) }
        service.syncNow()
        assertEquals(emptyList<OutboxRow>(), outbox())
        assertNull(fixture.entry(local.entryId))
        assertNull(fixture.fileStore.size(local.originalObjectIds[0]))
    }

    @Test
    fun deadLettersA404OnCreateEntry() = syncTest {
        enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()))
        handler = { httpFailure(404, ErrorCode.NOT_FOUND) }
        service.syncNow()
        assertEquals(OutboxState.DEAD, outbox()[0].state)
    }

    @Test
    fun stopsAndSignalsAnInvalidSessionOn401() = syncTest {
        enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()), row(OutboxKind.TRASH_ENTRY, fixture.randomId()))
        handler = { httpFailure(401, ErrorCode.UNAUTHENTICATED) }
        service.syncNow()
        assertEquals(1, invalid.get())
        assertEquals(0, pullCalls.get())
    }

    @Test
    fun treatsAnUnexpectedExecutorErrorAsAFailedAttemptInsteadOfCrashingThePass() = syncTest {
        val (id) = enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId()))
        handler = { throw Bug() }
        val report = service.syncNow()
        assertEquals(1, report.failed)
        val item = outbox()[0]
        assertEquals(id, item.id)
        assertEquals(1L, item.attempts)
        assertEquals("UNKNOWN", item.lastErrorCode)
    }

    @Test
    fun deadLettersAnItemWhoseExecutorKeepsThrowingUnexpectedErrors() = syncTest {
        val (id) = enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId(), attempts = (MAX_ATTEMPTS - 1).toLong()))
        handler = { throw Bug() }
        service.syncNow()
        val item = outbox()[0]
        assertEquals(id, item.id)
        assertEquals(OutboxState.DEAD, item.state)
        assertEquals("UNKNOWN", item.lastErrorCode)
        assertEquals(1, service.status().dead)
    }

    @Test
    fun doesNotCountAnAttemptWhenTheAccountIsLockedMidPush() = syncTest {
        val (id) = enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId(), attempts = (MAX_ATTEMPTS - 1).toLong()))
        handler = { throw AccountLockedException() }
        service.syncNow()
        assertEquals(listOf(id), calls.toList())
        val item = outbox()[0]
        assertEquals(id, item.id)
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals((MAX_ATTEMPTS - 1).toLong(), item.attempts)
        assertEquals(now + SYNC_INTERVAL_MS, item.nextAttemptAt)
    }

    @Test
    fun doesNotRunItemsThatAreNotDueYet() = syncTest {
        enqueue(row(OutboxKind.CREATE_ENTRY, fixture.randomId(), nextAttemptAt = now + 1))
        service.syncNow()
        assertEquals(emptyList<Long>(), calls.toList())
    }

    @Test
    fun pullsAfterPushingAndReportsPulledEntries() = syncTest {
        pullResults.addLast(Result.success(PullResult(4)))
        val report = service.syncNow()
        assertEquals(1, pullCalls.get())
        assertEquals(4, report.pulledEntries)
        assertTrue(report.durationMs >= 0)
    }

    @Test
    fun recordsLastSuccessAtAfterAPassThatReachedTheServer() = syncTest {
        service.syncNow()
        assertEquals(now, service.status().lastSuccessAt)
    }

    @Test
    fun flagsOfflineAndCountsAFailureWhenThePullCannotReachTheServer() = syncTest {
        pullResults.addLast(Result.failure(NetworkError()))
        val report = service.syncNow()
        assertTrue(service.status().offline)
        assertTrue(report.failed > 0)
        assertNull(service.status().lastSuccessAt)
    }

    @Test
    fun coalescesATriggerDuringAPassIntoOneMorePass() = syncTest {
        val gate = CompletableDeferred<Unit>()
        handler = {
            gate.await()
            ExecutorResult.Done
        }
        enqueue(row(OutboxKind.TRASH_ENTRY, fixture.randomId()))
        val first = launch(start = CoroutineStart.UNDISPATCHED) { service.syncNow() }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { service.syncNow() }
        gate.complete(Unit)
        joinAll(first, second)
        assertEquals(2, pullCalls.get())
    }

    @Test
    fun notifiesPassListenersOnlyForPassesThatReachedTheServer() = syncTest {
        val reports = CopyOnWriteArrayList<SyncReport>()
        service.onPassCompleted { report: SyncReport -> reports += report }
        pullResults.addLast(Result.failure(NetworkError()))
        service.syncNow()
        assertEquals(0, reports.size)
        service.syncNow()
        assertEquals(1, reports.size)
    }

    @Test
    fun doesNothingWhileTheAccountIsNotUnlocked() = syncTest {
        accountState.value = AccountState.Locked
        enqueue(row(OutboxKind.TRASH_ENTRY, fixture.randomId()))
        val report = service.syncNow()
        assertEquals(emptyList<Long>(), calls.toList())
        assertEquals(0, pullCalls.get())
        assertEquals(0, report.pushed)
        assertEquals(0, report.pulledEntries)
        assertEquals(0, report.failed)
    }

    @Test
    fun exposesRunningAndNotifiesStatusSubscribers() = syncTest {
        val seen = CopyOnWriteArrayList<Boolean>()
        service.subscribe { status: SyncStatus -> seen += status.running }
        service.syncNow()
        assertTrue(seen.contains(true))
        assertEquals(false, seen.last())
    }

    @Test
    fun reportsHeldBackUploadsAsWaitingForWifi() = syncTest {
        uploads.heldBack = 2
        service.syncNow()
        assertEquals(2, service.status().waitingForWifi)
    }

    @Test
    fun retryDeadResetsDeadItemsToQueuedWithZeroAttemptsAndDeadObjectsToLocal() = syncTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)), "", emptyList(), enqueue = false)
        enqueue(row(OutboxKind.CREATE_ENTRY, local.entryId, state = OutboxState.DEAD, attempts = 8, lastErrorCode = "QUOTA_EXCEEDED"))
        fixture.setObjectStates(ObjectUploadState.DEAD)
        service.retryDead()
        val item = outbox().single()
        assertEquals(OutboxState.QUEUED, item.state)
        assertEquals(0L, item.attempts)
        assertNull(item.lastErrorCode)
        assertTrue(fixture.objects().all { it.uploadState == ObjectUploadState.LOCAL })
        assertEquals(0, service.status().dead)
        assertEquals(1, service.status().queued)
    }

    @Test
    fun startRunsOnePassAtOnceAndRoutesUploaderEventsToTheCoordinator() = syncTest {
        service.start()
        runCurrent()
        assertEquals(1, pullCalls.get())
        uploader.emit(UploadEvent.Completed("x"))
        runCurrent()
        assertEquals(listOf<UploadEvent>(UploadEvent.Completed("x")), uploads.handledEvents.toList())
    }

    @Test
    fun runsAPassWhenTheAppBecomesActiveOrTheNetworkComesBack() = syncTest {
        service.start()
        runCurrent()
        assertEquals(1, pullCalls.get())
        lifecycle.fireActive(true)
        runCurrent()
        assertEquals(2, pullCalls.get())
        lifecycle.fireOnline()
        runCurrent()
        assertEquals(3, pullCalls.get())
        lifecycle.fireActive(false)
        advanceTimeBy(5)
        runCurrent()
        assertEquals(3, pullCalls.get())
    }

    @Test
    fun runsAPassEverySyncIntervalMsWhileActive() = syncTest {
        service.start()
        runCurrent()
        val before = pullCalls.get()
        advanceTimeBy(SYNC_INTERVAL_MS)
        runCurrent()
        assertEquals(before + 1, pullCalls.get())
        lifecycle.fireActive(false)
        advanceTimeBy(SYNC_INTERVAL_MS * 2)
        runCurrent()
        assertEquals(before + 1, pullCalls.get())
    }

    @Test
    fun debouncesLocalWritesIntoOnePassAfterSyncDebounceMs() = syncTest {
        service.notifyLocalWrite()
        service.notifyLocalWrite()
        advanceTimeBy(SYNC_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(0, pullCalls.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pullCalls.get())
    }

    @Test
    fun stopUnsubscribesFromLifecycleAndCancelsTimers() = syncTest {
        service.start()
        runCurrent()
        service.stop()
        val before = pullCalls.get()
        advanceTimeBy(SYNC_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(before, pullCalls.get())
        assertTrue(lifecycle.activeUnsubscribed)
    }
}
