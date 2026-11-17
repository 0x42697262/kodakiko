package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.account.checkPasswordPolicy
import dev.kodakiko.account.normalizeUsername
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PasswordMessage { CHANGED, WRONG_PASSWORD, FAILED }

@Immutable
data class PasswordState(
    val current: String,
    val next: String,
    val confirm: String,
    val busy: Boolean,
    val message: PasswordMessage?,
    val hints: List<PasswordRule>,
) {
    val mismatch: Boolean get() = confirm.isNotEmpty() && confirm != next

    val canSubmit: Boolean get() = hints.isEmpty() && next.isNotEmpty() && confirm == next && current.isNotEmpty()
}

sealed interface PasswordAction {
    data class SetCurrent(val value: String) : PasswordAction

    data class SetNext(val value: String) : PasswordAction

    data class SetConfirm(val value: String) : PasswordAction

    data object Submit : PasswordAction

    data object Back : PasswordAction
}

private fun wrongPassword(error: Throwable): Boolean =
    error is AuthError && (error.reason == AuthFailure.WRONG_PASSWORD || error.reason == AuthFailure.INVALID_CREDENTIALS)

class PasswordViewModel(private val deps: AccountSettingsDeps) : ViewModel() {
    private data class Form(val current: String = "", val next: String = "", val confirm: String = "", val busy: Boolean = false, val message: PasswordMessage? = null)

    private val form = MutableStateFlow(Form())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<PasswordState> = combine(form, deps.profile.username, ::stateOf).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS),
        stateOf(form.value, deps.profile.username.value),
    )

    fun onAction(action: PasswordAction) {
        when (action) {
            is PasswordAction.SetCurrent -> form.update { it.copy(current = action.value) }
            is PasswordAction.SetNext -> form.update { it.copy(next = action.value) }
            is PasswordAction.SetConfirm -> form.update { it.copy(confirm = action.value) }
            PasswordAction.Submit -> submit()
            PasswordAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun submit() {
        val current = stateOf(form.value, deps.profile.username.value)
        if (current.busy || !current.canSubmit) return
        form.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                deps.credentials.changePassword(current.current, current.next)
                PasswordMessage.CHANGED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (wrongPassword(error)) PasswordMessage.WRONG_PASSWORD else PasswordMessage.FAILED
            }
            form.update {
                if (message == PasswordMessage.CHANGED) Form(message = message) else it.copy(busy = false, message = message)
            }
        }
    }

    private fun stateOf(current: Form, username: String): PasswordState = PasswordState(
        current = current.current,
        next = current.next,
        confirm = current.confirm,
        busy = current.busy,
        message = current.message,
        hints = if (current.next.isEmpty()) emptyList() else checkPasswordPolicy(current.next, normalizeUsername(username)),
    )
}

fun deletionAllowed(typed: String, username: String, password: String): Boolean = typed.trim() == username && password.isNotEmpty()

enum class DeleteAccountError { WRONG_PASSWORD, FAILED }

@Immutable
data class DeleteAccountState(
    val local: Boolean,
    val graceDays: Long,
    val username: String,
    val typed: String,
    val password: String,
    val busy: Boolean,
    val error: DeleteAccountError?,
) {
    val canConfirm: Boolean get() = deletionAllowed(typed, username, password)
}

sealed interface DeleteAccountAction {
    data class SetTyped(val value: String) : DeleteAccountAction

    data class SetPassword(val value: String) : DeleteAccountAction

    data object Confirm : DeleteAccountAction

    data object Back : DeleteAccountAction
}

class DeleteAccountViewModel(private val deps: AccountSettingsDeps) : ViewModel() {
    private data class Form(val typed: String = "", val password: String = "", val busy: Boolean = false, val error: DeleteAccountError? = null)

    private val graceDays = deps.settings.config.current()?.accountDeletionGraceDays ?: SettingsConstants.DEFAULT_DELETION_GRACE_DAYS
    private val form = MutableStateFlow(Form())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<DeleteAccountState> = combine(form, deps.profile.username, deps.settings.account.mode, ::stateOf).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS),
        stateOf(form.value, deps.profile.username.value, deps.settings.account.mode.value),
    )

    fun onAction(action: DeleteAccountAction) {
        when (action) {
            is DeleteAccountAction.SetTyped -> form.update { it.copy(typed = action.value) }
            is DeleteAccountAction.SetPassword -> form.update { it.copy(password = action.value) }
            DeleteAccountAction.Confirm -> confirm()
            DeleteAccountAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun confirm() {
        val current = stateOf(form.value, deps.profile.username.value, deps.settings.account.mode.value)
        if (current.busy || !current.canConfirm) return
        form.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val error = try {
                remove(current.password, current.local)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DeleteAccountError.FAILED
            }
            form.update { it.copy(busy = false, error = error) }
        }
    }

    private suspend fun remove(password: String, local: Boolean): DeleteAccountError? {
        val credentials = deps.credentials
        if (!credentials.verifyPassword(password)) return DeleteAccountError.WRONG_PASSWORD
        if (local) {
            credentials.eraseLocal()
            channel.trySend(SettingsEvent.ReplaceAll(Route.Welcome))
        } else {
            credentials.requestAccountDeletion()
            channel.trySend(SettingsEvent.ReplaceAll(Route.SignIn))
        }
        return null
    }

    private fun stateOf(current: Form, username: String, mode: AccountMode): DeleteAccountState = DeleteAccountState(
        local = mode.isLocal(),
        graceDays = graceDays,
        username = username,
        typed = current.typed,
        password = current.password,
        busy = current.busy,
        error = current.error,
    )
}
