package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.launch

@Immutable
data class RecoverState(
    val showUsername: Boolean = true,
    val username: String = "",
    val words: String = "",
    val newPassword: String = "",
    val confirm: String = "",
    val hints: List<PasswordRule> = emptyList(),
    val mismatch: Boolean = false,
    val busy: Boolean = false,
    val error: BannerMessage? = null,
)

class RecoverViewModel(
    private val auth: AuthService,
    accountState: Observable<AccountState>,
) : FormViewModel<RecoverState>(RecoverState(showUsername = auth.mode.value != AccountMode.LOCAL)) {
    private var touched = false

    init {
        if (accountState.value == AccountState.Locked) {
            viewModelScope.launch {
                val username = attempt { auth.activeUsername() }.getOrNull().orEmpty()
                update { if (it.username.isEmpty()) checked(it.copy(username = username)) else it }
            }
        }
    }

    fun onUsernameChange(value: String) {
        update { checked(it.copy(username = value)) }
    }

    fun onWordsChange(value: String) {
        update { it.copy(words = value) }
    }

    fun onNewPasswordChange(value: String) {
        touched = true
        update { checked(it.copy(newPassword = value)) }
    }

    fun onConfirmChange(value: String) {
        update { checked(it.copy(confirm = value)) }
    }

    fun onSubmit() {
        touched = true
        update(::checked)
        val form = current
        if (passwordHints(form.newPassword, form.username, true).isNotEmpty() || form.confirm != form.newPassword) return
        if (form.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.recoverAccount(form.username, form.words, form.newPassword) }
                .onSuccess { emit(AuthEvent.ReplaceAll(Route.Home)) }
                .onFailure { failure -> update { checked(it.copy(error = errorBanner(failure), newPassword = "", confirm = "")) } }
            update { it.copy(busy = false) }
        }
    }

    private fun checked(state: RecoverState): RecoverState =
        state.copy(hints = passwordHints(state.newPassword, state.username, touched), mismatch = touched && state.confirm != state.newPassword)
}
