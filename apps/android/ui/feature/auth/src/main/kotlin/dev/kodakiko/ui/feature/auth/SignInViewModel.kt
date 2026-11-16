package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.launch

@Immutable
data class SignInState(
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: BannerMessage? = null,
    val deletionScheduled: String? = null,
)

class SignInViewModel(
    private val auth: AuthService,
    accountState: Observable<AccountState>,
) : FormViewModel<SignInState>(
    SignInState(
        username = (accountState.value as? AccountState.SignedOut)?.username.orEmpty(),
        deletionScheduled = auth.deletionScheduledFor()?.take(AuthConstants.DELETION_NOTICE_DATE_CHARS),
    ),
) {
    fun onUsernameChange(value: String) {
        update { it.copy(username = value) }
    }

    fun onPasswordChange(value: String) {
        update { it.copy(password = value) }
    }

    fun onSubmit() {
        val form = current
        if (form.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.signIn(form.username, form.password) }
                .onSuccess { emit(AuthEvent.ReplaceAll(Route.Home)) }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure), password = "") } }
            update { it.copy(busy = false) }
        }
    }

    fun onForgot() {
        emit(AuthEvent.Push(Route.Recover))
    }
}
