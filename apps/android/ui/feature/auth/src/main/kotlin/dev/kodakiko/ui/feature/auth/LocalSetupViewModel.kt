package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.launch

@Immutable
data class LocalSetupState(
    val understood: Boolean = false,
    val username: String = "",
    val password: String = "",
    val confirm: String = "",
    val hints: List<PasswordRule> = emptyList(),
    val mismatch: Boolean = false,
    val busy: Boolean = false,
    val error: BannerMessage? = null,
)

class LocalSetupViewModel(
    private val auth: AuthService,
) : FormViewModel<LocalSetupState>(LocalSetupState()) {
    private var touched = false

    fun onUnderstoodChange(value: Boolean) {
        update { it.copy(understood = value) }
    }

    fun onUsernameChange(value: String) {
        update { checked(it.copy(username = value)) }
    }

    fun onPasswordChange(value: String) {
        update { checked(it.copy(password = value)) }
    }

    fun onConfirmChange(value: String) {
        update { checked(it.copy(confirm = value)) }
    }

    fun onCreate() {
        touched = true
        update(::checked)
        val form = current
        if (!form.understood || form.hints.isNotEmpty() || form.mismatch || form.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.setUpLocal(form.username, form.password) }
                .onSuccess { emit(AuthEvent.ReplaceAll(Route.RecoveryKey)) }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure)) } }
            update { it.copy(busy = false) }
        }
    }

    private fun checked(state: LocalSetupState): LocalSetupState =
        state.copy(hints = passwordHints(state.password, state.username, touched), mismatch = touched && state.confirm != state.password)
}
