package dev.kodakiko.ui.feature.lock

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.migration.AdoptionStatus
import dev.kodakiko.data.migration.LegacyAdoption
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.lock.LockControl
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface PendingChanges {
    suspend fun count(): Int
}

sealed interface LockDialog {
    data object SignOut : LockDialog
}

sealed interface LockEvent {
    data class Push(val route: Route) : LockEvent
}

@Immutable
data class LockState(
    val username: String? = null,
    val passwordShown: Boolean = false,
    val password: String = "",
    val busy: Boolean = false,
    val wrongPassword: Boolean = false,
    val error: BannerMessage? = null,
    val updatedNotice: Boolean = false,
    val unreadable: Boolean = false,
    val dialog: LockDialog? = null,
)

class LockViewModel(
    private val auth: AuthService,
    private val lock: LockControl,
    private val adoption: LegacyAdoption,
    private val pendingChanges: PendingChanges,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LockState())
    private val channel = Channel<LockEvent>(Channel.BUFFERED)

    val state: StateFlow<LockState> = mutableState.asStateFlow()

    val events: Flow<LockEvent> = channel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val pending = attempt { withContext(ioDispatcher) { adoption.pendingAccount() } }.getOrNull()
            if (pending != null) {
                adopt(pending)
                return@launch
            }
            val username = attempt { auth.activeUsername() }.getOrNull()
            mutableState.update { it.copy(username = username) }
            if (lock.lockedInSession()) promptOnce()
        }
    }

    fun onUnlockWithDevice() {
        val current = mutableState.value
        if (current.unreadable || current.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.unlockWithDevice() }
                .onSuccess { unlocked -> if (!unlocked) mutableState.update { it.copy(passwordShown = true) } }
                .onFailure { failure -> mutableState.update { it.copy(error = errorBanner(failure)) } }
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun onUnlockWithPassword() {
        val current = mutableState.value
        if (current.unreadable) return
        if (!current.passwordShown) {
            mutableState.update { it.copy(passwordShown = true) }
            return
        }
        if (current.busy) return
        val password = current.password
        mutableState.update { it.copy(busy = true, wrongPassword = false, error = null) }
        viewModelScope.launch {
            attempt { auth.unlockWithPassword(password) }.onFailure { failure ->
                val wrong = failure is AuthError && failure.reason == AuthFailure.WRONG_PASSWORD
                mutableState.update {
                    it.copy(password = "", wrongPassword = wrong, error = if (wrong) null else errorBanner(failure))
                }
            }
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun onPasswordChange(value: String) {
        mutableState.update { it.copy(password = value) }
    }

    fun onForgot() {
        channel.trySend(LockEvent.Push(Route.Recover))
    }

    fun onSignOut() {
        viewModelScope.launch {
            val waiting = attempt { pendingChanges.count() }.getOrDefault(0)
            if (waiting > 0) mutableState.update { it.copy(dialog = LockDialog.SignOut) } else signOut()
        }
    }

    fun onSignOutConfirm() {
        signOut()
    }

    fun onDismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    private fun signOut() {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launch { attempt { auth.signOut() } }
    }

    private suspend fun promptOnce() {
        mutableState.update { it.copy(busy = true) }
        val unlocked = attempt { auth.unlockWithDevice() }.getOrDefault(false)
        mutableState.update { it.copy(busy = false, passwordShown = it.passwordShown || !unlocked) }
    }

    private suspend fun adopt(account: ActiveAccount) {
        mutableState.update { it.copy(username = account.username, busy = true) }
        val result = attempt { withContext(ioDispatcher) { adoption.run() } }.getOrNull()
        if (result == null || result.status in UNREADABLE) {
            mutableState.update { it.copy(busy = false, unreadable = true, passwordShown = false) }
            return
        }
        attempt { auth.restore() }
        mutableState.update { it.copy(busy = false, passwordShown = result.needsPassword, updatedNotice = result.needsPassword) }
    }

    private companion object {
        val UNREADABLE = setOf(AdoptionStatus.CORRUPT_DATABASE, AdoptionStatus.NEWER_DATABASE)
    }
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
