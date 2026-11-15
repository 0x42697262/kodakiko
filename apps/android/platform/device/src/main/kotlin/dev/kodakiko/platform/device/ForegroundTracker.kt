package dev.kodakiko.platform.device

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.CopyOnWriteArrayList

class ForegroundTracker(lifecycle: Lifecycle, private val now: () -> Long) {
    @Volatile
    private var foreground = isForegroundState(lifecycle.currentState)

    @Volatile
    private var lastBackground: Long? = null

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event -> update(isForegroundState(event.targetState)) })
    }

    fun isForeground(): Boolean = foreground

    fun lastBackgroundAt(): Long? = lastBackground

    fun subscribe(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    private fun update(next: Boolean) {
        if (next == foreground) return
        if (!next) lastBackground = now()
        foreground = next
        listeners.forEach { it(next) }
    }

    private fun isForegroundState(state: Lifecycle.State): Boolean = state.isAtLeast(Lifecycle.State.STARTED)
}
