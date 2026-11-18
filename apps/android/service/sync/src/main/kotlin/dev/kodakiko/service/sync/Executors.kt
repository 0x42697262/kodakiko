package dev.kodakiko.service.sync

import dev.kodakiko.contract.OutboxKind
import dev.kodakiko.data.db.ObjectRow
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.OutboxRow
import dev.kodakiko.data.db.RowStoreTx
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.entries.EntryFiles
import dev.kodakiko.sync.PushFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

sealed interface ExecutorResult {
    data object Done : ExecutorResult

    data object Waiting : ExecutorResult

    data class Failed(val failure: PushFailure) : ExecutorResult
}

fun interface PushExecutor {
    suspend fun execute(item: OutboxRow, deps: ExecutorDeps): ExecutorResult
}

fun interface ObjectUploadEnqueuer {
    suspend fun enqueueObjectUploads(objectIds: List<String>)
}

class ExecutorDeps(
    val http: SyncHttp,
    val rowStore: RowStorePort,
    val files: EntryFiles,
    val onEntriesChanged: (List<String>) -> Unit,
    val log: SyncLog,
    val uploads: ObjectUploadEnqueuer,
    val conflict: ConflictResolver,
    val ioDispatcher: CoroutineDispatcher,
) {
    internal suspend fun <T> call(call: ApiCall<T>): T = withContext(ioDispatcher) { http.request(call) }

    internal suspend fun <T> transaction(work: (RowStoreTx) -> T): T = withContext(ioDispatcher) { rowStore.transaction(work) }

    internal suspend fun deleteObjectFiles(objectIds: List<String>) {
        withContext(ioDispatcher) { deleteFiles(files, objectIds) }
    }
}

object PushExecutors {
    val ALL: Map<OutboxKind, PushExecutor> = mapOf(
        OutboxKind.CREATE_ENTRY to guarded(::createEntry),
        OutboxKind.UPDATE_ENTRY_META to guarded(::updateEntryMeta),
        OutboxKind.ADD_MEDIA to guarded(::addMedia),
        OutboxKind.REMOVE_MEDIA to guarded(::removeMedia),
        OutboxKind.TRASH_ENTRY to guarded(::trashEntry),
        OutboxKind.RESTORE_ENTRY to guarded(::restoreEntry),
        OutboxKind.DELETE_ENTRY to guarded(::deleteEntry),
    )
}

internal fun toPushFailure(error: Throwable): PushFailure? = when (error) {
    is NetworkError -> PushFailure.Network
    is ApiError -> httpFailureOf(error)
    else -> null
}

internal fun httpFailureOf(error: ApiError): PushFailure.Http = PushFailure.Http(error.status, error.code, error.retryAfterSeconds)

private fun guarded(run: suspend (OutboxRow, ExecutorDeps) -> ExecutorResult): PushExecutor =
    PushExecutor { item, deps ->
        try {
            run(item, deps)
        } catch (error: NetworkError) {
            ExecutorResult.Failed(PushFailure.Network)
        } catch (error: ApiError) {
            ExecutorResult.Failed(httpFailureOf(error))
        }
    }

private val NEEDS_ENQUEUE: Set<ObjectUploadState> = setOf(ObjectUploadState.LOCAL, ObjectUploadState.QUEUED)

private suspend fun objectsOf(deps: ExecutorDeps, mediaIds: List<String>): List<ObjectRow> =
    deps.transaction { tx -> mediaIds.flatMap { tx.objects.byMedia(it) } }

internal suspend fun mediaIsPosted(deps: ExecutorDeps, mediaId: String): Boolean =
    objectsOf(deps, listOf(mediaId)).any { it.uploadState != ObjectUploadState.LOCAL }

internal suspend fun advanceUploads(deps: ExecutorDeps, mediaIds: List<String>): Boolean {
    val pending = objectsOf(deps, mediaIds).filter { it.uploadState in NEEDS_ENQUEUE }
    if (pending.isNotEmpty()) deps.uploads.enqueueObjectUploads(pending.map { it.id })
    return objectsOf(deps, mediaIds).all { it.uploadState == ObjectUploadState.UPLOADED }
}
