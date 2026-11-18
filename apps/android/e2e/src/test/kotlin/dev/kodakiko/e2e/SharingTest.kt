package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.data.db.ObjectVariant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharingTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun aSharesAJournalWithBRemovalTrashRestoreAndLeaveReachB() = e2e {
        val a = signUpDevice("A")
        val b = signUpDevice("B")
        a.config.refresh()
        b.config.refresh()
        a.syncUntilIdle()
        b.syncUntilIdle()
        val photo = randomMedia(E2eConstants.SMALL_MEDIA_BYTES)
        val first = a.createSyncedEntry(EntryInput(listOf(photo), "first", listOf("shared")))
        val second = a.createSyncedEntry(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "second", emptyList()))

        val journalId = a.sharing.createJournal("Family")
        val added = a.sharing.addEntries(journalId, listOf(first.entryId, second.entryId))
        assertEquals(listOf(first.entryId, second.entryId), added.added)
        a.sharing.share(journalId, a.sharing.lookupRecipient(b.account.username))

        b.syncUntilIdle()
        assertEquals(listOf(first.entryId, second.entryId).sorted(), b.entryIds())
        assertEquals("first", b.readMetadata(first.entryId).note)
        assertArrayEquals(photo, b.downloadAndDecrypt(first.entryId, first.mediaIdAt(0), ObjectVariant.ORIGINAL))

        a.sharing.removeEntries(journalId, listOf(second.entryId))
        b.syncUntilIdle()
        assertEquals(listOf(first.entryId), b.entryIds())

        a.entries.trash(listOf(first.entryId))
        a.syncUntilIdle()
        b.syncUntilIdle()
        assertEquals(emptyList<String>(), b.entryIds())

        a.entries.restore(listOf(first.entryId))
        a.syncUntilIdle()
        b.syncUntilIdle()
        assertEquals(listOf(first.entryId), b.entryIds())

        b.sharing.leave(journalId)
        b.syncUntilIdle()
        assertEquals(emptyList<String>(), b.entryIds())
        val collections = b.transaction { tx -> tx.collections.all() }
        assertFalse(journalId in collections.map { it.id })
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val harness = E2eServer(mapOf(E2eConstants.FLAG_SHARING to E2eConstants.FLAG_ON, E2eConstants.FLAG_PUBLIC_LINKS to E2eConstants.FLAG_OFF))
    }
}
