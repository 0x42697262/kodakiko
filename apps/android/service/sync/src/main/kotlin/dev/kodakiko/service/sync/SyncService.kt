package dev.kodakiko.service.sync

import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntriesSyncPort
import dev.kodakiko.service.entries.EntryFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

data class SyncReport(val pushed: Int, val pulledEntries: Int, val failed: Int, val durationMs: Long)

data class SyncStatus(
    val running: Boolean,
    val queued: Int,
    val dead: Int,
    val waitingForWifi: Int,
    val lastSuccessAt: Long?,
    val offline: Boolean,
)

interface SyncService : EntriesSyncPort {
    fun start()

    fun stop()

    suspend fun syncNow(): SyncReport

    fun onPassCompleted(listener: (SyncReport) -> Unit): () -> Unit

    fun status(): SyncStatus

    fun subscribe(listener: (SyncStatus) -> Unit): () -> Unit

    suspend fun retryDead()
}

class SyncServiceDeps(
    val http: SyncHttp,
    val rowStore: RowStorePort,
    val files: EntryFiles,
    val uploader: Uploader,
    val uploads: UploadCoordinator,
    val pull: Pull,
    val conflict: ConflictResolver,
    val executors: Map<OutboxKind, PushExecutor> = PushExecutors.ALL,
    val onEntriesChanged: (List<String>) -> Unit,
    val clock: SyncClock,
    val random: () -> Double,
    val lifecycle: SyncLifecycle,
    val accountState: Observable<AccountState>,
    val onSessionInvalid: () -> Unit,
    val log: SyncLog,
    val localOnly: () -> Boolean = { false },
    val scope: CoroutineScope,
    val ioDispatcher: CoroutineDispatcher,
)
