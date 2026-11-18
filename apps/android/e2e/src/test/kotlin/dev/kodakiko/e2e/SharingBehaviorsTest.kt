package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.data.db.CollectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharingBehaviorsTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    private class SharedSetup(val a: E2eDevice, val b: E2eDevice, val journalId: String, val first: CreatedEntry, val second: CreatedEntry)

    private suspend fun shareTwoEntries(): SharedSetup {
        val a = signUpDevice("A")
        val b = signUpDevice("B")
        a.config.refresh()
        b.config.refresh()
        a.syncUntilIdle()
        b.syncUntilIdle()
        val first = a.createSyncedEntry(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "first", listOf("shared")))
        val second = a.createSyncedEntry(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "second", emptyList()))
        val journalId = a.sharing.createJournal("Family")
        a.sharing.addEntries(journalId, listOf(first.entryId, second.entryId))
        return SharedSetup(a, b, journalId, first, second)
    }

    @Test
    fun b080WhenASharesAJournalWithBAndBothCompareTheSecurityCodeThenBSeesItsEntriesReadOnlyAndTheCodesMatch() = e2e {
        val setup = shareTwoEntries()
        val (a, b) = setup.a to setup.b
        val recipient = a.sharing.lookupRecipient(b.account.username)

        a.sharing.share(setup.journalId, recipient)
        b.syncUntilIdle()

        assertEquals(listOf(setup.first.entryId, setup.second.entryId).sorted(), b.entryIds())
        assertEquals("first", b.readMetadata(setup.first.entryId).note)
        val journalOnB = b.transaction { tx -> tx.collections.get(setup.journalId) } ?: error("B has no journal")
        assertEquals(CollectionRole.VIEWER, journalOnB.role)
        assertEquals(a.userId, journalOnB.ownerId)
        assertEquals(CollectionRole.OWNER, a.transaction { tx -> tx.collections.get(setup.journalId) }?.role)
        assertEquals(b.sharing.myFingerprint(), recipient.fingerprint)
        assertEquals(a.sharing.myFingerprint(), b.sharing.lookupRecipient(a.account.username).fingerprint)
    }

    @Test
    fun b081WhenARemovesAnEntryThenRemovesBThenBLosesThatEntryThenTheWholeJournal() = e2e {
        val setup = shareTwoEntries()
        val (a, b) = setup.a to setup.b
        a.sharing.share(setup.journalId, a.sharing.lookupRecipient(b.account.username))
        b.syncUntilIdle()
        assertEquals(listOf(setup.first.entryId, setup.second.entryId).sorted(), b.entryIds())

        a.sharing.removeEntries(setup.journalId, listOf(setup.second.entryId))
        b.syncUntilIdle()
        assertEquals(listOf(setup.first.entryId), b.entryIds())

        a.sharing.unshare(setup.journalId, b.userId)
        b.syncUntilIdle()
        assertEquals(emptyList<String>(), b.entryIds())
        assertFalse(setup.journalId in b.transaction { tx -> tx.collections.all() }.map { it.id })
        assertEquals(emptyList<String>(), a.sharing.listShares(setup.journalId).map { it.recipientUserId })
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val harness = E2eServer(mapOf(E2eConstants.FLAG_SHARING to E2eConstants.FLAG_ON, E2eConstants.FLAG_PUBLIC_LINKS to E2eConstants.FLAG_OFF))
    }
}
