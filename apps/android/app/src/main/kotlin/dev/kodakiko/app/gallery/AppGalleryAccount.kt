package dev.kodakiko.app.gallery

import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.ui.feature.gallery.GalleryAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AppGalleryAccount(
    private val auth: AuthService,
    private val lockControl: LockControl,
    accountState: Observable<AccountState>,
    scope: CoroutineScope,
) : GalleryAccount {
    override val username: StateFlow<String> = accountState.flow
        .map(::usernameOf)
        .stateIn(scope, SharingStarted.Eagerly, usernameOf(accountState.value))

    override val mode: StateFlow<AccountMode> = auth.mode.flow

    override suspend fun lockNow() {
        lockControl.lockNow()
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    private fun usernameOf(state: AccountState): String = (state as? AccountState.Unlocked)?.username.orEmpty()
}
