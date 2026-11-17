package dev.kodakiko.app.sync

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.LockReason
import dev.kodakiko.service.lock.LockService
import dev.kodakiko.service.sync.Uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SessionInvalidation(
    private val lockService: Lazy<LockService>,
    private val accountState: Observable<AccountState>,
    private val scope: CoroutineScope,
    private val uploaders: List<Uploader>,
) {
    fun invalidate() {
        scope.launch {
            try {
                lockService.value.lock(LockReason.SESSION_INVALID)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            accountState.value = AccountState.SignedOut()
            cancelUploads(uploaders)
        }
    }
}

suspend fun cancelUploads(uploaders: List<Uploader>) {
    for (uploader in uploaders) {
        try {
            uploader.cancelAll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }
}
