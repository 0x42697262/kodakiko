package dev.kodakiko.service.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

class Observable<T>(initial: T) {
    private val lock = Any()
    private val state = MutableStateFlow(initial)
    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    val flow: StateFlow<T> = state.asStateFlow()

    var value: T
        get() = state.value
        set(next) {
            synchronized(lock) {
                state.value = next
                listeners.forEach { it(next) }
            }
        }

    fun subscribe(listener: (T) -> Unit): () -> Unit {
        val entry: (T) -> Unit = { listener(it) }
        listeners += entry
        return { listeners.remove(entry) }
    }
}
