package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.launch

@Immutable
data class SignUpState(
    val asksForInvite: Boolean = false,
    val invite: String = "",
    val username: String = "",
    val password: String = "",
    val confirm: String = "",
    val hints: List<PasswordRule> = emptyList(),
    val mismatch: Boolean = false,
    val busy: Boolean = false,
    val error: BannerMessage? = null,
)

class SignUpViewModel(
    private val auth: AuthService,
    config: ConfigService,
    private val inviteToken: Observable<String?>,
) : FormViewModel<SignUpState>(
    SignUpState(asksForInvite = inviteToken.value == null && (config.current()?.signupMode ?: SignupMode.INVITE) == SignupMode.INVITE),
) {
    private var touched = false

    fun onInviteChange(value: String) {
        update { it.copy(invite = value) }
    }

    fun onUsernameChange(value: String) {
        update { checked(it.copy(username = value)) }
    }

    fun onPasswordChange(value: String) {
        touched = true
        update { checked(it.copy(password = value)) }
    }

    fun onConfirmChange(value: String) {
        update { checked(it.copy(confirm = value)) }
    }

    fun onSubmit() {
        touched = true
        update(::checked)
        val form = current
        if (passwordHints(form.password, form.username, true).isNotEmpty() || form.confirm != form.password) return
        if (form.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { create(form) }
                .onSuccess { emit(AuthEvent.ReplaceAll(Route.RecoveryKey)) }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure)) } }
            update { it.copy(busy = false) }
        }
    }

    private suspend fun create(form: SignUpState) {
        val token = inviteToken.value ?: if (form.asksForInvite) extractInviteToken(form.invite) else null
        if (form.asksForInvite && token == null) throw AuthError(AuthFailure.INVITE_INVALID)
        auth.signUp(form.username, form.password, token)
    }

    private fun checked(state: SignUpState): SignUpState =
        state.copy(hints = passwordHints(state.password, state.username, touched), mismatch = touched && state.confirm != state.password)
}
