package dev.kodakiko.service.index

import dev.kodakiko.crypto.Sodium
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.EntryRow
import dev.kodakiko.data.db.MediaRow
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.IndexedEntry
import dev.kodakiko.service.account.AccountStorage
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

interface IndexService {
    val version: StateFlow<IndexVersion>

    suspend fun build(): IndexBuildReport

    fun index(): GalleryIndex?

    fun status(): IndexStatus

    fun subscribe(listener: () -> Unit): () -> Unit

    fun clear()
}

class IndexServiceDeps(
    val sodium: Sodium,
    val rowStore: RowStorePort,
    val entryKeys: EntryKeySource,
    val events: EntriesChangedSource,
    val now: () -> Long,
    val userId: () -> String?,
    val scope: CoroutineScope,
    val ioDispatcher: CoroutineDispatcher,
    val onBuilt: (IndexBuildReport) -> Unit = {},
)

class IndexTagSuggestions(private val indexService: IndexService) {
    fun suggest(prefix: String): List<String> = indexService.index()?.tagSuggestions(prefix, IndexServiceConstants.TAG_SUGGESTIONS_MAX) ?: emptyList()
}

class DefaultIndexService(private val deps: IndexServiceDeps) : IndexService {
    private class Built(val index: GalleryIndex, val homeKnown: Boolean, val counts: IndexedRowsCount)

    private class RowsFor(val rows: IndexRows, val missing: List<String>)

    private val guard = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val pending = LinkedHashSet<String>()
    private val flushLock = Mutex()
    private val versionState = MutableStateFlow(IndexVersion(IndexStatus.IDLE, 0, 0))

    @Volatile
    private var currentStatus = IndexStatus.IDLE

    @Volatile
    private var current: GalleryIndex? = null

    private var homeKnown = false
    private var generation = 0
    private var timer: Job? = null

    private val open: MetadataOpener = { row -> openEntryMetadata(deps.sodium, deps.entryKeys.entryKey(row.id), row.id, row.metadataBlob) }

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    init {
        deps.events.onEntriesChanged(::onEntriesChanged)
    }

    override suspend fun build(): IndexBuildReport {
        val started = synchronized(guard) {
            generation += 1
            currentStatus = IndexStatus.BUILDING
            current = null
            generation
        }
        val startedAt = deps.now()
        notifyListeners()
        try {
            val userId = requireUserId()
            val built = withContext(deps.ioDispatcher) { buildIndex(userId) }
            val report = IndexBuildReport(built.counts.entries, built.counts.unreadable, deps.now() - startedAt)
            val hasPending = synchronized(guard) {
                if (started != generation) return report
                current = built.index
                homeKnown = built.homeKnown
                currentStatus = IndexStatus.READY
                pending.isNotEmpty()
            }
            notifyListeners()
            deps.onBuilt(report)
            if (hasPending) schedule()
            return report
        } catch (failure: Throwable) {
            val reset = synchronized(guard) {
                if (started == generation) currentStatus = IndexStatus.IDLE
                started == generation
            }
            if (reset) notifyListeners()
            throw failure
        }
    }

    override fun index(): GalleryIndex? = current

    override fun status(): IndexStatus = currentStatus

    override fun subscribe(listener: () -> Unit): () -> Unit {
        val entry: () -> Unit = { listener() }
        listeners += entry
        return { listeners.remove(entry) }
    }

    override fun clear() {
        synchronized(guard) {
            generation += 1
            timer?.cancel()
            timer = null
            pending.clear()
            current = null
            homeKnown = false
            currentStatus = IndexStatus.IDLE
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        versionState.value = synchronized(guard) { IndexVersion(currentStatus, generation, current?.version() ?: 0) }
        listeners.forEach { it() }
    }

    private fun requireUserId(): String = deps.userId() ?: throw AccountLockedException()

    private suspend fun buildIndex(userId: String): Built {
        val home = resolveHome(userId)
        val rows = deps.rowStore.transaction(::readAllRows)
        val index = GalleryIndex(home ?: IndexServiceConstants.UNKNOWN_HOME_COLLECTION_ID)
        val counts = indexEntryRows(rows, userId, open, index::upsert)
        return Built(index, home != null, counts)
    }

    private fun resolveHome(userId: String): String? {
        val stored = AccountStorage.readString(deps.rowStore, StorageKeys.ACCOUNT_HOME_COLLECTION_ID)
        if (stored != null) return stored
        val collections = deps.rowStore.transaction { tx -> tx.collections.all() }
        return collections.firstOrNull { it.kind == CollectionKind.HOME && it.role == CollectionRole.OWNER && it.ownerId == userId }?.id
    }

    private fun onEntriesChanged(entryIds: List<String>) {
        val ready = synchronized(guard) {
            if (currentStatus == IndexStatus.IDLE) return
            pending.addAll(entryIds)
            currentStatus == IndexStatus.READY
        }
        if (ready) schedule()
    }

    private fun schedule() {
        synchronized(guard) {
            timer?.cancel()
            timer = deps.scope.launch {
                delay(IndexServiceConstants.INDEX_UPDATE_DEBOUNCE_MS)
                val self = coroutineContext.job
                val entryIds = synchronized(guard) {
                    if (timer !== self) return@launch
                    timer = null
                    pending.toList().also { pending.clear() }
                }
                deps.scope.launch { flushLock.withLock { runCatching { flush(entryIds) } } }
            }
        }
    }

    private suspend fun flush(entryIds: List<String>) {
        if (entryIds.isEmpty()) return
        val started = synchronized(guard) {
            if (currentStatus != IndexStatus.READY || current == null) {
                if (currentStatus == IndexStatus.BUILDING) pending.addAll(entryIds)
                return
            }
            generation
        }
        val userId = requireUserId()
        val knownHome = synchronized(guard) { homeKnown }
        if (!knownHome && withContext(deps.ioDispatcher) { resolveHome(userId) } != null) {
            if (started == synchronized(guard) { generation }) build()
            return
        }
        val updates = mutableListOf<Pair<IndexedEntry, String>>()
        val missing = withContext(deps.ioDispatcher) {
            val found = deps.rowStore.transaction { tx -> readRowsFor(tx, entryIds) }
            indexEntryRows(found.rows, userId, open, { entry, noteText -> updates.add(entry to noteText) })
            found.missing
        }
        val applied = synchronized(guard) {
            val index = current
            if (started != generation || index == null) return
            missing.forEach(index::remove)
            updates.forEach { (entry, noteText) -> index.upsert(entry, noteText) }
            true
        }
        if (applied) notifyListeners()
    }

    private fun readAllRows(tx: RowStoreTx): IndexRows =
        IndexRows(
            entries = tx.entries.all(),
            memberships = tx.memberships.all(),
            media = tx.media.all(),
            objects = tx.objects.all(),
        )

    private fun readRowsFor(tx: RowStoreTx, entryIds: List<String>): RowsFor {
        val entries = mutableListOf<EntryRow>()
        val memberships = mutableListOf<MembershipRow>()
        val media = mutableListOf<MediaRow>()
        val objects = mutableListOf<ObjectRow>()
        val missing = mutableListOf<String>()
        for (entryId in entryIds) {
            val row = tx.entries.get(entryId)
            if (row == null) {
                missing.add(entryId)
                continue
            }
            entries.add(row)
            memberships.addAll(tx.memberships.byEntry(entryId))
            val mediaRows = tx.media.byEntry(entryId)
            media.addAll(mediaRows)
            for (mediaRow in mediaRows) objects.addAll(tx.objects.byMedia(mediaRow.id))
        }
        return RowsFor(IndexRows(entries, memberships, media, objects), missing)
    }
}
