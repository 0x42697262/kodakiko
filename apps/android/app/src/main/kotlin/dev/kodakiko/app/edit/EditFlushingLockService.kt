package dev.kodakiko.app.edit

import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.LockReason
import dev.kodakiko.service.lock.LockService
import dev.kodakiko.ui.feature.edit.EditFlushes
import kotlinx.coroutines.withTimeoutOrNull

class EditFlushingLockService(
    private val delegate: LockService,
    private val flushes: EditFlushes,
    private val accountState: Observable<AccountState>,
) : LockService by delegate {
    override suspend fun lock(reason: LockReason) {
        if (accountState.value is AccountState.Unlocked && !delegate.isLocking()) {
            withTimeoutOrNull(EditAdapterConstants.LOCK_FLUSH_TIMEOUT_MS) { flushes.flushAll() }
        }
        delegate.lock(reason)
    }
}
