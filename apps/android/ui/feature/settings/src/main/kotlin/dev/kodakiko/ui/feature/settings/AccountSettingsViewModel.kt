package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.UserDto
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.sync.SyncStatus
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
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class AccountStorageUse(val usedBytes: Long, val quotaBytes: Long, val asOf: String?)

@Immutable
sealed interface RecoverySheet {
    data class Prompt(val password: String, val wrong: Boolean) : RecoverySheet

    data class Words(val words: List<String>) : RecoverySheet
}

@Immutable
data class AccountSettingsState(
    val username: String,
    val local: Boolean,
    val host: String,
    val storage: AccountStorageUse?,
    val securityCode: String,
    val changingServer: Boolean,
    val unsynced: Int,
    val recovery: RecoverySheet?,
)

sealed interface AccountSettingsAction {
    data class Open(val route: Route) : AccountSettingsAction

    data object Refresh : AccountSettingsAction

    data object AskChangeServer : AccountSettingsAction

    data object DismissChangeServer : AccountSettingsAction

    data object ConfirmChangeServer : AccountSettingsAction

    data object ShowRecovery : AccountSettingsAction

    data class SetRecoveryPassword(val password: String) : AccountSettingsAction

    data object RevealRecovery : AccountSettingsAction

    data object CloseRecovery : AccountSettingsAction

    data object Back : AccountSettingsAction
}

internal fun hostOf(url: String?): String {
    val parsed = url?.let { runCatching { URI(it) }.getOrNull() } ?: return ""
    val host = parsed.host ?: return ""
    val defaultPort = if (parsed.scheme == SettingsConstants.HTTPS_SCHEME) SettingsConstants.HTTPS_DEFAULT_PORT else SettingsConstants.HTTP_DEFAULT_PORT
    val port = parsed.port
    return if (port == SettingsConstants.NO_PORT || port == defaultPort) host else host + SettingsConstants.PORT_SEPARATOR + port
}

internal fun AccountMode.isLocal(): Boolean = this == AccountMode.LOCAL

class AccountSettingsViewModel(private val deps: AccountSettingsDeps) : ViewModel() {
    private data class Screen(
        val host: String = "",
        val securityCode: String = "",
        val refreshedAt: Long? = null,
        val stale: Boolean = false,
        val changingServer: Boolean = false,
        val recovery: RecoverySheet? = null,
    )

    private val settings = deps.settings
    private val profile = deps.profile
    private val screen = MutableStateFlow(Screen())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)
    private val locks = AtomicInteger(0)

    val highlightKey: String? = settings.highlight.take(Route.SettingsAccount)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<AccountSettingsState> = combine(profile.username, settings.account.mode, profile.user, settings.sync.status, screen, ::stateOf)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS),
            stateOf(profile.username.value, settings.account.mode.value, profile.user.value, settings.sync.status.value, screen.value),
        )

    init {
        viewModelScope.launch {
            profile.unlocked.collect { unlocked ->
                if (!unlocked) {
                    locks.incrementAndGet()
                    dropWords()
                }
            }
        }
        viewModelScope.launch {
            if (!local()) {
                val host = hostOf(attempt { profile.serverUrl() })
                val code = attempt { profile.securityCode() }.orEmpty()
                screen.update { it.copy(host = host, securityCode = code) }
            }
            refresh()
        }
    }

    fun onAction(action: AccountSettingsAction) {
        when (action) {
            is AccountSettingsAction.Open -> channel.trySend(SettingsEvent.Open(action.route))
            AccountSettingsAction.Refresh -> viewModelScope.launch { refresh() }
            AccountSettingsAction.AskChangeServer -> screen.update { it.copy(changingServer = true) }
            AccountSettingsAction.DismissChangeServer -> screen.update { it.copy(changingServer = false) }
            AccountSettingsAction.ConfirmChangeServer -> changeServer()
            AccountSettingsAction.ShowRecovery -> screen.update { it.copy(recovery = RecoverySheet.Prompt(password = "", wrong = false)) }
            is AccountSettingsAction.SetRecoveryPassword -> screen.update { current ->
                val prompt = current.recovery as? RecoverySheet.Prompt
                if (prompt == null) current else current.copy(recovery = prompt.copy(password = action.password))
            }
            AccountSettingsAction.RevealRecovery -> reveal()
            AccountSettingsAction.CloseRecovery -> screen.update { it.copy(recovery = null) }
            AccountSettingsAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun local(): Boolean = settings.account.mode.value.isLocal()

    private suspend fun refresh() {
        if (local()) return
        val fresh = try {
            profile.refreshMe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            screen.update { it.copy(stale = true) }
            return
        }
        val now = deps.nowMs()
        screen.update { if (fresh == null) it.copy(stale = true) else it.copy(stale = false, refreshedAt = now) }
    }

    private fun changeServer() {
        screen.update { it.copy(changingServer = false) }
        viewModelScope.launch {
            val changed = attempt { deps.credentials.changeServer() } != null
            if (changed) channel.trySend(SettingsEvent.ReplaceAll(Route.Welcome))
        }
    }

    private fun reveal() {
        val prompt = screen.value.recovery as? RecoverySheet.Prompt ?: return
        screen.update { it.copy(recovery = prompt.copy(wrong = false)) }
        val started = locks.get()
        viewModelScope.launch {
            val words = attempt { deps.credentials.revealRecoveryWords(prompt.password) }
            if (started != locks.get() || !profile.unlocked.value) {
                dropWords()
                return@launch
            }
            screen.update { current ->
                val open = current.recovery as? RecoverySheet.Prompt ?: return@update current
                current.copy(recovery = if (words == null) open.copy(wrong = true) else RecoverySheet.Words(words))
            }
        }
    }

    private fun dropWords() {
        screen.update { if (it.recovery == null) it else it.copy(recovery = RecoverySheet.Prompt(password = "", wrong = false)) }
    }

    private fun stateOf(username: String, mode: AccountMode, user: UserDto?, sync: SyncStatus, current: Screen): AccountSettingsState {
        val local = mode.isLocal()
        val quota = user?.quotaBytes ?: settings.config.current()?.defaultQuotaBytes ?: SettingsConstants.DEFAULT_QUOTA_BYTES
        val asOf = current.refreshedAt?.takeIf { current.stale }?.let(::syncedAtText)
        return AccountSettingsState(
            username = username,
            local = local,
            host = current.host,
            storage = if (local || user == null) null else AccountStorageUse(user.usedBytes, quota, asOf),
            securityCode = if (local) "" else current.securityCode,
            changingServer = current.changingServer,
            unsynced = sync.queued,
            recovery = current.recovery,
        )
    }
}
