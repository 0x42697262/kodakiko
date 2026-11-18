package dev.kodakiko.ui.feature.edit

import java.util.concurrent.CopyOnWriteArrayList

class EditFlushes {
    private val pages = CopyOnWriteArrayList<suspend () -> Unit>()

    fun register(flush: suspend () -> Unit): () -> Unit {
        pages += flush
        return { pages.remove(flush) }
    }

    suspend fun flushAll() {
        for (flush in pages) flush()
    }
}
