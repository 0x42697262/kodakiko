package dev.kodakiko.service.sync

import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

class NotificationPromptDeps(
    val rowStore: RowStorePort,
    val request: (suspend () -> Boolean)?,
    val ioDispatcher: CoroutineDispatcher,
)

class NotificationPrompt(private val deps: NotificationPromptDeps) : NotificationPromptPort {
    private val lock = Any()
    private var pending: CompletableDeferred<Unit>? = null

    val visible: Observable<Boolean> = Observable(false)

    override suspend fun ensureAsked() {
        val request = deps.request ?: return
        val (shared, owner) = synchronized(lock) {
            pending?.let { it to false } ?: CompletableDeferred<Unit>().also { pending = it }.let { it to true }
        }
        if (!owner) return shared.await()
        try {
            ask(request)
            shared.complete(Unit)
        } catch (failure: Throwable) {
            shared.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(lock) { pending = null }
        }
    }

    private suspend fun ask(request: suspend () -> Boolean) {
        if (answered()) return
        visible.value = true
        try {
            val granted = request()
            withContext(deps.ioDispatcher) { deps.rowStore.kvSetJson(StorageKeys.DEVICE_NOTIFICATION_PERMISSION_ASKED, JsonPrimitive(granted)) }
        } finally {
            visible.value = false
        }
    }

    private suspend fun answered(): Boolean {
        val stored = withContext(deps.ioDispatcher) { deps.rowStore.kvGetJson(StorageKeys.DEVICE_NOTIFICATION_PERMISSION_ASKED) }
        if (stored == null) return false
        (stored as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalStateException(SyncText.INVALID_STORED_VALUE)
        return true
    }
}
