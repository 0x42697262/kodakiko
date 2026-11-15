package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.data.secure.AccountMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalOnlyTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun aJournalKeptWithoutAServerUploadsAfterConnectingAndOpensOnASecondDevice() = e2e {
        val a = setUpLocalDevice("A")
        val photo = randomMedia(E2eConstants.SMALL_MEDIA_BYTES)
        val first = a.createEntryLocally(EntryInput(listOf(photo), "kept offline", listOf("local")))
        val second = a.createEntryLocally(EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), "second", emptyList()))
        a.sync.syncNow()
        assertEquals(emptyList<String>(), a.fetcher.requests())

        a.auth.connectServer(serverInput = server.baseUrl, inviteToken = null, username = a.account.username, password = a.account.password)
        assertEquals(AccountMode.SERVER, a.auth.mode.value)
        a.syncUntilIdle()

        val b = signInDevice("B", a.account)
        assertEquals(a.userId, b.userId)
        b.syncUntilIdle()
        assertEquals(listOf(first.entryId, second.entryId).sorted(), b.entryIds())
        assertEquals("kept offline", b.readMetadata(first.entryId).note)
        assertArrayEquals(photo, b.downloadAndDecrypt(first.entryId, first.mediaIdAt(0), ObjectVariant.ORIGINAL))
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
