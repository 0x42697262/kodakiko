package dev.kodakiko.ui.feature.auth

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

abstract class FormViewModel<S>(initial: S) : ViewModel() {
    private val mutableState = MutableStateFlow(initial)
    private val channel = Channel<AuthEvent>(Channel.BUFFERED)

    val state: StateFlow<S> = mutableState.asStateFlow()

    val events: Flow<AuthEvent> = channel.receiveAsFlow()

    protected val current: S get() = mutableState.value

    protected fun update(change: (S) -> S) {
        mutableState.update(change)
    }

    protected fun emit(event: AuthEvent) {
        channel.trySend(event)
    }
}

internal suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
