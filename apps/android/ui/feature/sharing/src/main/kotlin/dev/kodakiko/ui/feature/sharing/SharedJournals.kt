package dev.kodakiko.ui.feature.sharing

import androidx.compose.runtime.Immutable
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.index.GalleryFilter
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.index.GalleryScope
import dev.kodakiko.index.GalleryView
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.sharing.CollectionName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import java.text.Collator

@Immutable
data class SharedJournal(
    val id: String,
    val name: CollectionName,
    val role: CollectionRole,
    val ownerUsername: String,
    val entryCount: Int,
)

internal fun collectionFilter(collectionId: String): GalleryFilter =
    GalleryFilter(GalleryScope.Collection(collectionId), GalleryView.JOURNAL, emptyList(), null, null, "")

internal fun collectionEntryIds(index: GalleryIndex?, collectionId: String): List<String> =
    index?.query(collectionFilter(collectionId))?.flatMap { it.entryIds }.orEmpty()

private fun nameOrder(): Comparator<SharedJournal> {
    val collator = Collator.getInstance()
    return Comparator { left, right ->
        val leftName = (left.name as? CollectionName.Named)?.name
        val rightName = (right.name as? CollectionName.Named)?.name
        when {
            leftName != null && rightName != null -> collator.compare(leftName, rightName)
            leftName != null -> SharingConstants.SORT_BEFORE
            rightName != null -> SharingConstants.SORT_AFTER
            else -> 0
        }
    }
}

internal suspend fun loadJournals(deps: SharingDeps): List<SharedJournal> {
    val index = deps.index.index()
    val journals = deps.rows.load().map { row ->
        SharedJournal(
            id = row.id,
            name = deps.names.get(row.id),
            role = row.role,
            ownerUsername = row.ownerUsername,
            entryCount = collectionEntryIds(index, row.id).size,
        )
    }
    return journals.sortedWith(nameOrder())
}

internal suspend fun <T> attempt(work: suspend () -> T): Result<T> =
    try {
        Result.success(work())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

internal fun configFlow(config: ConfigService): Flow<PublicConfigDto?> = callbackFlow {
    val unsubscribe = config.subscribe { trySend(it) }
    trySend(config.current())
    awaitClose { unsubscribe() }
}

internal fun reloadTriggers(deps: SharingDeps, reloads: Flow<Int>): Flow<Unit> =
    combine(deps.index.version, deps.syncedAt, reloads) { _, _, _ -> }
