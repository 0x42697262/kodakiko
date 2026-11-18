package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.crypto.RECOVERY_WORD_COUNT
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Immutable
data class RecoveryKeyState(
    val words: List<String>? = null,
    val confirming: Boolean = false,
    val positions: List<Int> = emptyList(),
    val typed: List<String> = emptyList(),
    val mismatch: Boolean = false,
    val busy: Boolean = false,
    val error: BannerMessage? = null,
)

class RecoveryKeyViewModel(
    private val auth: AuthService,
    private val random: RandomSource,
    unlocked: Flow<Boolean> = flowOf(true),
) : FormViewModel<RecoveryKeyState>(RecoveryKeyState()) {
    init {
        viewModelScope.launch {
            unlocked.distinctUntilChanged().collectLatest { open -> if (open) load() else dropWords() }
        }
    }

    override fun onCleared() {
        dropWords()
        super.onCleared()
    }

    private suspend fun load() {
        val words = attempt { auth.pendingRecoveryWords() }.getOrNull()
        if (words == null) emit(AuthEvent.ReplaceAll(Route.Home)) else update { it.copy(words = words) }
    }

    private fun dropWords() {
        update { it.copy(words = null, confirming = false, positions = emptyList(), typed = emptyList(), mismatch = false) }
    }

    fun onWrittenDown() {
        if (current.words == null) return
        val positions = pickConfirmPositions(random, RECOVERY_WORD_COUNT, AuthConstants.RECOVERY_CONFIRM_COUNT)
        update { it.copy(confirming = true, positions = positions, typed = positions.map { "" }, mismatch = false) }
    }

    fun onTypedChange(index: Int, value: String) {
        update { state -> state.copy(typed = state.typed.mapIndexed { position, entry -> if (position == index) value else entry }) }
    }

    fun onConfirm() {
        val form = current
        if (form.busy) return
        val words = form.words ?: return
        val matches = form.positions.withIndex().all { (index, position) ->
            jsTrim(form.typed.getOrElse(index) { "" }).lowercase() == words.getOrNull(position - 1)
        }
        update { it.copy(mismatch = !matches) }
        if (!matches) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            attempt { auth.confirmRecoveryKeySaved() }
                .onSuccess {
                    dropWords()
                    emit(AuthEvent.ReplaceAll(Route.Home))
                }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure)) } }
            update { it.copy(busy = false) }
        }
    }
}
