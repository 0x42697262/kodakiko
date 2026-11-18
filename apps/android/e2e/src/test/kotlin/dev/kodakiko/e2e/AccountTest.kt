package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountTest : E2eTestBase() {
    override val server: E2eServer get() = harness

    private fun smallEntry(note: String) = EntryInput(listOf(randomMedia(E2eConstants.SMALL_MEDIA_BYTES)), note, emptyList())

    @Test
    fun b004WhenSomeoneSignsInWithAWrongPasswordOrAnUnknownUsernameThenBothGetTheSameAnswer() = e2e {
        val a = signUpDevice("A")
        val other = chosenDevice("B", newAccount())

        val wrongPassword = authFailure { other.auth.signIn(a.account.username, E2eConstants.NEW_PASSWORD) }
        val unknownUser = authFailure { other.auth.signIn(other.account.username, other.account.password) }

        assertEquals(AuthFailure.INVALID_CREDENTIALS, wrongPassword.reason)
        assertEquals(wrongPassword.reason, unknownUser.reason)
        assertTrue(other.accountState.value is AccountState.SignedOut)
    }

    @Test
    fun b005WhenTheUserChangesThePasswordThenOtherSessionsAreSignedOutAndTheNewPasswordUnlocksEverywhere() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry(NOTE))
        b.syncUntilIdle()

        a.auth.changePassword(E2eConstants.E2E_PASSWORD, E2eConstants.NEW_PASSWORD)

        a.syncUntilIdle()
        assertTrue(a.accountState.value is AccountState.Unlocked)
        b.sync.syncNow()
        assertTrue(b.accountState.value is AccountState.SignedOut)

        a.accountState.value = AccountState.Locked
        assertEquals(AuthFailure.WRONG_PASSWORD, authFailure { a.auth.unlockWithPassword(E2eConstants.E2E_PASSWORD) }.reason)
        a.auth.unlockWithPassword(E2eConstants.NEW_PASSWORD)
        assertEquals(a.userId, a.unlocked().userId)
        assertEquals(NOTE, a.readMetadata(entry.entryId).note)

        b.auth.signIn(b.account.username, E2eConstants.NEW_PASSWORD)
        b.syncUntilIdle()
        assertEquals(NOTE, b.readMetadata(entry.entryId).note)
        val third = chosenDevice("C", a.account)
        assertEquals(AuthFailure.INVALID_CREDENTIALS, authFailure { third.auth.signIn(a.account.username, E2eConstants.E2E_PASSWORD) }.reason)
    }

    @Test
    fun b006WhenTheUserEntersThe24WordsAndANewPasswordThenTheJournalOpensWithTheNewPasswordAndOldSessionsEnd() = e2e {
        val a = signUpDevice("A")
        a.syncUntilIdle()
        val entry = a.createSyncedEntry(smallEntry(NOTE))
        val b = signInDevice("B", a.account)
        b.syncUntilIdle()
        val recovering = chosenDevice("C", a.account.copy(password = E2eConstants.NEW_PASSWORD))

        recovering.auth.recoverAccount(a.account.username, a.recoveryWords.joinToString(WORD_SEPARATOR), E2eConstants.NEW_PASSWORD)

        assertEquals(a.userId, recovering.unlocked().userId)
        recovering.syncUntilIdle()
        assertEquals(NOTE, recovering.readMetadata(entry.entryId).note)
        a.sync.syncNow()
        b.sync.syncNow()
        assertTrue(a.accountState.value is AccountState.SignedOut)
        assertTrue(b.accountState.value is AccountState.SignedOut)
        val withNewPassword = signInDevice("D", a.account.copy(password = E2eConstants.NEW_PASSWORD))
        withNewPassword.syncUntilIdle()
        assertEquals(NOTE, withNewPassword.readMetadata(entry.entryId).note)
        val withOldPassword = chosenDevice("E", a.account)
        assertEquals(
            AuthFailure.INVALID_CREDENTIALS,
            authFailure { withOldPassword.auth.signIn(a.account.username, E2eConstants.E2E_PASSWORD) }.reason,
        )
    }

    @Test
    fun b010WhenTheUserRequestsDeletionThenTheDeviceSignsOutAndSignInOffersKeepMyAccount() = e2e {
        val (a, b) = startPair()
        val entry = a.createSyncedEntry(smallEntry(NOTE))

        val dueAt = a.auth.requestAccountDeletion()

        assertEquals(dueAt, a.auth.deletionScheduledFor())
        assertEquals(AccountState.SignedOut(a.account.username), a.accountState.value)
        b.sync.syncNow()
        assertTrue(b.accountState.value is AccountState.SignedOut)
        val returning = chosenDevice("C", a.account)
        returning.auth.signIn(a.account.username, a.account.password)
        assertEquals(AccountState.PendingDeletion, returning.accountState.value)

        returning.auth.cancelDeletion()

        assertEquals(a.userId, returning.unlocked().userId)
        returning.syncUntilIdle()
        assertEquals(NOTE, returning.readMetadata(entry.entryId).note)
    }

    @Test
    fun b104WhenTheUserChangesTheServerThenTheDeviceSignsOutAndAsksForTheNewServer() = e2e {
        val a = signUpDevice("A")
        a.syncUntilIdle()
        val entry = a.createSyncedEntry(smallEntry(NOTE))
        val accountKey = a.activeAccountKey.value ?: error("no account key")
        val databaseFile = KodakikoDatabase.databaseFile(a.context, accountHash(a.sodium, accountKey))
        assertTrue(databaseFile.isFile)

        a.auth.changeServer()

        assertEquals(AccountState.NoServer, a.accountState.value)
        assertNull(a.globalStore.serverUrl())
        assertNull(a.globalStore.serverConfigJson())
        assertNull(a.globalStore.activeAccount())
        assertNull(a.sessionStore.load(accountKey))
        assertNull(a.activeAccountKey.value)
        assertFalse(databaseFile.exists())

        a.chooseServer()
        assertTrue(a.accountState.value is AccountState.SignedOut)
        a.auth.signIn(a.account.username, a.account.password)
        a.syncUntilIdle()
        assertEquals(listOf(entry.entryId), a.entryIds())
    }

    companion object {
        private const val NOTE = "kept across accounts"
        private const val WORD_SEPARATOR = " "

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer()
    }
}
