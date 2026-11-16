package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import kotlinx.coroutines.launch

@Immutable
data class ConnectServerState(
    val server: String = "",
    val invite: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val done: Boolean = false,
    val error: BannerMessage? = null,
) {
    val canConnect: Boolean get() = !done && jsTrim(server).isNotEmpty() && password.isNotEmpty()
}

class ConnectServerViewModel(
    private val auth: AuthService,
    accountState: Observable<AccountState>,
) : FormViewModel<ConnectServerState>(ConnectServerState(username = (accountState.value as? AccountState.Unlocked)?.username.orEmpty())) {
    fun onServerChange(value: String) {
        update { it.copy(server = value) }
    }

    fun onInviteChange(value: String) {
        update { it.copy(invite = value) }
    }

    fun onUsernameChange(value: String) {
        update { it.copy(username = value) }
    }

    fun onPasswordChange(value: String) {
        update { it.copy(password = value) }
    }

    fun onConnect() {
        val form = current
        if (form.busy || !form.canConnect) return
        val inviteToken = if (jsTrim(form.invite).isEmpty()) null else extractInviteToken(form.invite)
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.connectServer(form.server, inviteToken, form.username, form.password) }
                .onSuccess { update { it.copy(password = "", done = true) } }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure)) } }
            update { it.copy(busy = false) }
        }
    }
}
