package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.data.db.EntryLocalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class B022Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun whenTheUserAddsANoteAndTagsAndSavesWhileOfflineThenTheEntryAppearsAtOnceAndSyncsWhenOnline() = e2e {
        val (a, b) = startPair()
        val changed = CopyOnWriteArrayList<String>()
        a.onEntriesChanged { changed.addAll(it) }
        a.fetcher.setOffline(true)

        val entry = a.createEntryLocally(
            EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES), randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), NOTE, TAGS),
        )

        assertTrue(entry.entryId in changed)
        val pending = a.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.PENDING_CREATE, pending.localState)
        assertEquals(E2eConstants.NO_REVISION, pending.baseRevision)
        assertNull(pending.trashedAt)
        val local = a.readMetadata(entry.entryId)
        assertEquals(NOTE, local.note)
        assertEquals(TAGS.sorted(), local.tags)
        assertEquals(2, local.media.size)
        assertNull(server.findServerEntry(entry.entryId))

        a.sync.syncNow()
        assertTrue(a.sync.status().offline)
        assertNull(server.findServerEntry(entry.entryId))
        assertEquals(EntryLocalState.PENDING_CREATE, a.readEntry(entry.entryId)?.localState)

        a.fetcher.setOffline(false)
        a.syncUntilIdle()

        assertEquals(0, a.outboxRows().size)
        val synced = a.readEntry(entry.entryId) ?: error("missing entry")
        assertEquals(EntryLocalState.SYNCED, synced.localState)
        assertEquals(E2eConstants.FIRST_REVISION, synced.revision)
        val onServer = server.findServerEntry(entry.entryId) ?: error("missing server entry")
        assertEquals(E2eConstants.FIRST_REVISION, onServer.revision)
        assertNull(onServer.trashedAt)

        b.syncUntilIdle()
        val onB = b.readMetadata(entry.entryId)
        assertEquals(NOTE, onB.note)
        assertEquals(TAGS.sorted(), onB.tags)
        assertEquals(entry.mediaIds, onB.media.map { it.mediaId })
    }

    companion object {
        private const val NOTE = "Picnic by the lake"
        private val TAGS = listOf("lake", "family")

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
