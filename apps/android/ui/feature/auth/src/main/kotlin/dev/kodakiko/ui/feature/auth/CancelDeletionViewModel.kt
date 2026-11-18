package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.launch
import java.time.ZoneId

fun interface DeletionDueAt {
    suspend fun read(): String?
}

@Immutable
data class CancelDeletionState(
    val date: String = "",
    val busy: Boolean = false,
    val error: BannerMessage? = null,
)

class CancelDeletionViewModel(
    private val auth: AuthService,
    dueAt: DeletionDueAt,
    zone: ZoneId,
) : FormViewModel<CancelDeletionState>(CancelDeletionState()) {
    init {
        viewModelScope.launch {
            val stored = attempt { dueAt.read() }.getOrNull() ?: return@launch
            update { it.copy(date = formatDeletionDate(stored, zone)) }
        }
    }

    fun onKeep() {
        run(Route.Home) { auth.cancelDeletion() }
    }

    fun onSignOut() {
        run(Route.Welcome) { auth.signOut() }
    }

    private fun run(next: Route, action: suspend () -> Unit) {
        if (current.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { action() }
                .onSuccess { emit(AuthEvent.ReplaceAll(next)) }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure)) } }
            update { it.copy(busy = false) }
        }
    }
}
