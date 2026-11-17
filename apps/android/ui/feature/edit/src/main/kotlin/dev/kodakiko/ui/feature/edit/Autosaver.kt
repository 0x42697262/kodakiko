package dev.kodakiko.ui.feature.edit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Autosaver<T>(
    private val scope: CoroutineScope,
    private val write: suspend (T) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private class Pending<T>(val value: T)

    private val lock = Any()
    private var pending: Pending<T>? = null
    private var timer: Job? = null
    private var chain: Job = Job().apply { complete() }

    fun change(value: T, debounced: Boolean) {
        synchronized(lock) {
            timer?.cancel()
            timer = null
            pending = Pending(value)
            if (debounced) {
                timer = scope.launch {
                    delay(EditConstants.AUTOSAVE_DEBOUNCE_MS)
                    fire()
                }
            }
        }
        if (!debounced) fire()
    }

    suspend fun flush() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
        fire()
        val last = synchronized(lock) { chain }
        last.join()
    }

    private fun fire() {
        synchronized(lock) {
            val next = pending ?: return
            pending = null
            val previous = chain
            chain = scope.launch {
                previous.join()
                run(next.value)
            }
        }
    }

    private suspend fun run(value: T) {
        try {
            write(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onError(failure)
        }
    }
}
