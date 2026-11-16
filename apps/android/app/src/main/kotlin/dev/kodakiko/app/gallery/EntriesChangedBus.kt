package dev.kodakiko.app.gallery

import dev.kodakiko.service.index.EntriesChangedSource
import java.util.concurrent.CopyOnWriteArrayList

class EntriesChangedBus : EntriesChangedSource {
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    override fun onEntriesChanged(listener: (List<String>) -> Unit): () -> Unit {
        val entry: (List<String>) -> Unit = { listener(it) }
        listeners += entry
        return { listeners.remove(entry) }
    }

    fun emit(entryIds: List<String>) {
        listeners.forEach { it(entryIds) }
    }
}
