package dev.kodakiko.service.sync

import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.OutboxState
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.entries.AccountLockedException
import dev.kodakiko.sync.FailureDecision
import dev.kodakiko.sync.MAX_ATTEMPTS
import dev.kodakiko.sync.OutboxItemState
import dev.kodakiko.sync.OutboxItemView
import dev.kodakiko.sync.PUSH_CONCURRENCY
import dev.kodakiko.sync.PushFailure
import dev.kodakiko.sync.SYNC_DEBOUNCE_MS
import dev.kodakiko.sync.SYNC_INTERVAL_MS
import dev.kodakiko.sync.classifyPushFailure
import dev.kodakiko.sync.nextAttemptAt
import dev.kodakiko.sync.planPushBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val DROP_REMOVES_ENTRY: Set<OutboxKind> = setOf(
    OutboxKind.UPDATE_ENTRY_META,
    OutboxKind.TRASH_ENTRY,
    OutboxKind.RESTORE_ENTRY,
    OutboxKind.DELETE_ENTRY,
)

private class PassTotals {
    var pushed = 0
    var failed = 0
    var reached = false
    var offline: Boolean? = null
    var halted = false
}

private class PlannedItem(val row: OutboxRow, val kind: OutboxKind)

private fun viewOf(row: OutboxRow): OutboxItemView? {
    val kind = outboxKindOf(row) ?: return null
    val state = if (row.state == OutboxState.DEAD) OutboxItemState.DEAD else OutboxItemState.QUEUED
    return OutboxItemView(row.id, kind, entryIdOfPayload(row.payload), state, row.nextAttemptAt.toDouble())
}

private fun errorCodeOf(failure: PushFailure): String = when (failure) {
    PushFailure.Network -> SyncErrorCodes.NETWORK
    is PushFailure.Http -> failure.code.wire
}

private val EMPTY_REPORT = SyncReport(0, 0, 0, SyncServiceConstants.EMPTY_DURATION_MS)

class DefaultSyncService(private val deps: SyncServiceDeps) : SyncService {
    private val rowStore = deps.rowStore
    private val uploads = deps.uploads
    private val clock = deps.clock
    private val scope = deps.scope
    private val lock = Any()
    private val executorDeps = ExecutorDeps(
        http = deps.http,
        rowStore = rowStore,
        files = deps.files,
        onEntriesChanged = deps.onEntriesChanged,
        log = deps.log,
        uploads = uploads,
        conflict = deps.conflict,
        ioDispatcher = deps.ioDispatcher,
    )
    private val statusListeners = CopyOnWriteArrayList<(SyncStatus) -> Unit>()
    private val passListeners = CopyOnWriteArrayList<(SyncReport) -> Unit>()
    private val kickedAt = ConcurrentHashMap<String, Long>()
    private val inFlightEntries: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var current = SyncStatus(running = false, queued = 0, dead = 0, waitingForWifi = 0, lastSuccessAt = null, offline = false)
    private var started = false
    private var active = true
    private var passInFlight: CompletableDeferred<SyncReport>? = null
    private var runAgain = false
    private var debounceJob: Job? = null
    private var intervalJob: Job? = null
    private var unsubscribers: List<() -> Unit> = emptyList()
    private var uploaderUnsubscribe: (() -> Unit)? = null

    init {
        uploads.onObjectUploaded { entryId ->
            kickedAt[entryId] = clock.now()
            launchSafely {
                makeEntryDue(entryId)
                schedulePass()
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            active = true
        }
        ensureListening()
        val subscriptions = listOf(deps.lifecycle.subscribeActive(::onActiveChanged), deps.lifecycle.subscribeOnline(::triggerPass))
        synchronized(lock) { unsubscribers = subscriptions }
        startInterval()
        triggerPass()
    }

    override fun stop() {
        val (subscriptions, uploaderSubscription) = synchronized(lock) {
            started = false
            active = false
            debounceJob?.cancel()
            debounceJob = null
            intervalJob?.cancel()
            intervalJob = null
            val taken = unsubscribers to uploaderUnsubscribe
            unsubscribers = emptyList()
            uploaderUnsubscribe = null
            taken
        }
        subscriptions.forEach { it() }
        uploaderSubscription?.invoke()
    }

    override suspend fun syncNow(): SyncReport {
        val (handle, owner) = synchronized(lock) {
            passInFlight?.let {
                runAgain = true
                it to false
            } ?: CompletableDeferred<SyncReport>().also {
                passInFlight = it
                runAgain = false
            }.let { it to true }
        }
        if (owner) launchPasses(handle)
        return handle.await()
    }

    override fun onPassCompleted(listener: (SyncReport) -> Unit): () -> Unit {
        val entry: (SyncReport) -> Unit = { listener(it) }
        passListeners += entry
        return { passListeners.remove(entry) }
    }

    override fun notifyLocalWrite() {
        launchSafely { refreshCounts() }
        restartDebounce()
    }

    override fun status(): SyncStatus = synchronized(lock) { current }

    override fun subscribe(listener: (SyncStatus) -> Unit): () -> Unit {
        val entry: (SyncStatus) -> Unit = { listener(it) }
        statusListeners += entry
        return { statusListeners.remove(entry) }
    }

    override suspend fun retryDead() {
        transaction { tx ->
            val now = clock.now()
            for (row in tx.outbox.all()) {
                if (row.state == OutboxState.DEAD) {
                    tx.outbox.update(
                        row.copy(state = OutboxState.QUEUED, attempts = 0, nextAttemptAt = now, lastErrorCode = null),
                    )
                }
            }
            for (objectRow in tx.objects.all()) {
                if (objectRow.uploadState == ObjectUploadState.DEAD) tx.objects.put(objectRow.copy(uploadState = ObjectUploadState.LOCAL))
            }
        }
        refreshCounts()
        schedulePass()
    }

    override fun isEntryInFlight(entryId: String): Boolean = entryId in inFlightEntries

    private suspend fun <T> transaction(work: (RowStoreTx) -> T): T = withContext(deps.ioDispatcher) { rowStore.transaction(work) }

    private fun setStatus(change: (SyncStatus) -> SyncStatus) {
        val next = synchronized(lock) {
            current = change(current)
            current
        }
        statusListeners.forEach { it(next) }
    }

    private suspend fun refreshCounts() {
        val counts = transaction { tx -> tx.outbox.countByState() }
        val waitingForWifi = uploads.heldBackCount()
        setStatus {
            it.copy(
                queued = counts[OutboxState.QUEUED]?.toInt() ?: 0,
                dead = counts[OutboxState.DEAD]?.toInt() ?: 0,
                waitingForWifi = waitingForWifi,
            )
        }
    }

    private suspend fun refreshCountsQuietly() {
        try {
            refreshCounts()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            reportFailure(failure)
        }
    }

    private fun reportFailure(error: Throwable) {
        deps.log.warn(SyncLogEvents.PASS_FAILED, mapOf(SyncLogFields.ERROR to error::class.simpleName))
    }

    private fun launchSafely(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                reportFailure(failure)
            }
        }
    }

    private fun triggerPass() {
        launchSafely { syncNow() }
    }

    private fun restartDebounce() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(SYNC_DEBOUNCE_MS)
                triggerPass()
            }
        }
    }

    private fun schedulePass() {
        if (!synchronized(lock) { started }) return
        restartDebounce()
    }

    private fun onActiveChanged(isActive: Boolean) {
        synchronized(lock) { active = isActive }
        if (isActive) {
            startInterval()
            triggerPass()
        } else {
            synchronized(lock) {
                intervalJob?.cancel()
                intervalJob = null
            }
        }
    }

    private fun startInterval() {
        synchronized(lock) {
            if (intervalJob != null) return
            intervalJob = scope.launch {
                while (isActive) {
                    delay(SYNC_INTERVAL_MS)
                    if (synchronized(lock) { active }) triggerPass()
                }
            }
        }
    }

    private fun ensureListening() {
        synchronized(lock) {
            if (uploaderUnsubscribe != null) return
            uploaderUnsubscribe = deps.uploader.subscribe(::onUploadEvent)
        }
    }

    private fun onUploadEvent(event: UploadEvent) {
        scope.launch {
            try {
                uploads.handleEvent(event)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                deps.log.warn(SyncLogEvents.EVENT_HANDLER_FAILED, mapOf(SyncLogFields.ERROR to failure::class.simpleName))
                return@launch
            }
            refreshCountsQuietly()
        }
    }

    private suspend fun makeEntryDue(entryId: String) {
        val now = clock.now()
        transaction { tx ->
            for (row in tx.outbox.all()) {
                val due = row.state == OutboxState.QUEUED && isUploadItem(row) && entryIdOfPayload(row.payload) == entryId && row.nextAttemptAt > now
                if (due) tx.outbox.update(row.copy(nextAttemptAt = now))
            }
        }
    }

    private fun launchPasses(handle: CompletableDeferred<SyncReport>) {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                handle.complete(runPasses())
            } catch (failure: Throwable) {
                abandonPass(handle, failure)
                if (failure is CancellationException) throw failure
            }
        }
        job.invokeOnCompletion { cause -> if (!handle.isCompleted) abandonPass(handle, cause ?: CancellationException()) }
    }

    private suspend fun runPasses(): SyncReport {
        while (true) {
            val report = onePass()
            val finished = synchronized(lock) {
                if (runAgain) {
                    runAgain = false
                    false
                } else {
                    passInFlight = null
                    true
                }
            }
            if (finished) return report
        }
    }

    private fun abandonPass(handle: CompletableDeferred<SyncReport>, cause: Throwable) {
        synchronized(lock) { if (passInFlight === handle) passInFlight = null }
        handle.completeExceptionally(cause)
    }

    private suspend fun onePass(): SyncReport {
        val startedAt = clock.now()
        if (deps.accountState.value !is AccountState.Unlocked || deps.localOnly()) return EMPTY_REPORT
        setStatus { it.copy(running = true) }
        val totals = PassTotals()
        var pulledEntries = 0
        try {
            ensureListening()
            try {
                uploads.reconcileUploads()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                reportFailure(failure)
            }
            pushDrain(totals)
            if (!totals.halted) pulledEntries = pullAll(totals)
        } finally {
            val (clean, offline) = synchronized(totals) { (totals.reached && totals.offline != true) to totals.offline }
            setStatus { it.copy(running = false, offline = offline ?: it.offline, lastSuccessAt = if (clean) clock.now() else it.lastSuccessAt) }
            withContext(NonCancellable) { refreshCountsQuietly() }
        }
        val report = synchronized(totals) { SyncReport(totals.pushed, pulledEntries, totals.failed, clock.now() - startedAt) }
        if (synchronized(totals) { totals.reached }) passListeners.forEach { it(report) }
        return report
    }

    private suspend fun pushDrain(totals: PassTotals) {
        coroutineScope {
            val running = HashMap<Long, Job>()
            val finished = Channel<Long>(Channel.UNLIMITED)
            while (true) {
                val rows = transaction { tx -> tx.outbox.all() }
                val views = rows.mapNotNull(::viewOf)
                val capacity = PUSH_CONCURRENCY - running.size
                val ids = planPushBatch(views, inFlightEntries.toSet(), clock.now().toDouble(), capacity).filter { it !in running }.toSet()
                val planned = rows.filter { it.id in ids }.mapNotNull { row -> outboxKindOf(row)?.let { PlannedItem(row, it) } }
                for (item in planned) {
                    val entryId = entryIdOfPayload(item.row.payload)
                    if (entryId != null) inFlightEntries += entryId
                    running[item.row.id] = launch {
                        try {
                            runItem(item.row, item.kind, totals)
                        } finally {
                            if (entryId != null) inFlightEntries -= entryId
                            finished.trySend(item.row.id)
                        }
                    }
                }
                if (running.isEmpty()) return@coroutineScope
                running.remove(finished.receive())
                while (true) running.remove(finished.tryReceive().getOrNull() ?: break)
                if (synchronized(totals) { totals.halted }) {
                    running.values.toList().joinAll()
                    return@coroutineScope
                }
            }
        }
    }

    private suspend fun runItem(row: OutboxRow, kind: OutboxKind, totals: PassTotals) {
        val startedAt = clock.now()
        val result = try {
            val executor = deps.executors[kind] ?: throw IllegalStateException(SyncText.UNKNOWN_OUTBOX_KIND)
            executor.execute(row, executorDeps)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: AccountLockedException) {
            updateRow(row.id) { it.copy(nextAttemptAt = clock.now() + SYNC_INTERVAL_MS) }
            return
        } catch (failure: Exception) {
            reportFailure(failure)
            synchronized(totals) { totals.failed += 1 }
            val attempts = row.attempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                applyDead(row, SyncErrorCodes.UNKNOWN)
            } else {
                applyRetry(row, nextAttemptAt(attempts.toInt(), clock.now().toDouble(), deps.random).toLong(), SyncErrorCodes.UNKNOWN)
            }
            return
        }
        applyResult(row, kind, result, startedAt, totals)
    }

    private suspend fun applyResult(row: OutboxRow, kind: OutboxKind, result: ExecutorResult, startedAt: Long, totals: PassTotals) {
        when (result) {
            ExecutorResult.Done -> {
                synchronized(totals) {
                    totals.pushed += 1
                    totals.reached = true
                    totals.offline = false
                }
                transaction { tx -> tx.outbox.delete(row.id) }
            }
            ExecutorResult.Waiting -> {
                val entryId = entryIdOfPayload(row.payload)
                val kicked = entryId != null && (kickedAt[entryId] ?: SyncServiceConstants.NOT_KICKED) >= startedAt
                if (entryId != null && kicked) kickedAt.remove(entryId)
                val now = clock.now()
                updateRow(row.id) { it.copy(nextAttemptAt = if (kicked) now else now + SYNC_INTERVAL_MS) }
            }
            is ExecutorResult.Failed -> applyFailure(row, kind, result.failure, totals)
        }
    }

    private suspend fun applyFailure(row: OutboxRow, kind: OutboxKind, failure: PushFailure, totals: PassTotals) {
        val now = clock.now()
        val attempts = (row.attempts + 1).toInt()
        val decision = classifyPushFailure(kind, failure, attempts, now.toDouble(), deps.random)
        synchronized(totals) {
            totals.offline = failure == PushFailure.Network
            totals.reached = totals.reached || failure is PushFailure.Http
        }
        val code = errorCodeOf(failure)
        when (decision) {
            is FailureDecision.Retry -> {
                countFailure(totals)
                applyRetry(row, decision.at.toLong(), code)
            }
            FailureDecision.Conflict -> {
                countFailure(totals)
                applyRetry(row, nextAttemptAt(attempts, now.toDouble(), deps.random).toLong(), code)
            }
            FailureDecision.Dead -> {
                countFailure(totals)
                applyDead(row, code)
            }
            FailureDecision.Drop -> dropItem(row, kind)
            FailureDecision.SignedOut -> {
                countFailure(totals)
                val halt = synchronized(totals) {
                    val first = !totals.halted
                    totals.halted = true
                    first
                }
                if (halt) {
                    stop()
                    deps.onSessionInvalid()
                }
            }
        }
    }

    private fun countFailure(totals: PassTotals) {
        synchronized(totals) { totals.failed += 1 }
    }

    private suspend fun updateRow(id: Long, change: (OutboxRow) -> OutboxRow) {
        transaction { tx ->
            val row = tx.outbox.all().firstOrNull { it.id == id } ?: return@transaction
            tx.outbox.update(change(row))
        }
    }

    private suspend fun applyRetry(row: OutboxRow, at: Long, code: String) {
        updateRow(row.id) { it.copy(attempts = row.attempts + 1, nextAttemptAt = at, lastErrorCode = code) }
    }

    private suspend fun applyDead(row: OutboxRow, code: String) {
        updateRow(row.id) { it.copy(state = OutboxState.DEAD, attempts = row.attempts + 1, lastErrorCode = code) }
    }

    private suspend fun dropItem(row: OutboxRow, kind: OutboxKind) {
        val entryId = entryIdOfPayload(row.payload)
        val objectIds = transaction { tx ->
            val removed = if (entryId != null && kind in DROP_REMOVES_ENTRY) removeEntryRows(tx, entryId) else emptyList()
            tx.outbox.delete(row.id)
            removed
        }
        withContext(deps.ioDispatcher) { deleteFiles(deps.files, objectIds) }
        if (entryId != null) deps.onEntriesChanged(listOf(entryId))
    }

    private suspend fun pullAll(totals: PassTotals): Int =
        try {
            val result = deps.pull.pull()
            synchronized(totals) {
                totals.reached = true
                totals.offline = false
            }
            result.pulledEntries
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: NetworkError) {
            synchronized(totals) {
                totals.failed += 1
                totals.offline = true
            }
            0
        } catch (failure: ApiError) {
            synchronized(totals) {
                totals.failed += 1
                totals.reached = true
            }
            0
        }
}
