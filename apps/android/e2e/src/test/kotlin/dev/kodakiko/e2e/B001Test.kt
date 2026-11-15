package dev.kodakiko.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.ServerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class B001Test : E2eTestBase() {
    override val server: E2eServer get() = harness

    @Test
    fun givenAnAdminInviteLinkWhenANewUserSignsUpThenTheAccountExistsThe24WordsAreShownAndTheJournalIsEmptyAndUnlocked() = e2e {
        val invite = server.seedInvite()
        val a = buildDevice("A", newAccount())
        val choice = a.auth.chooseServer(invite.url)
        assertTrue(choice.toString(), choice is ServerChoice.Ok)
        assertEquals(invite.token, (choice as ServerChoice.Ok).inviteToken)
        assertTrue(a.accountState.value is AccountState.SignedOut)
        assertEquals(
            AuthFailure.PASSWORD_POLICY,
            authFailure { a.auth.signUp(a.account.username, SHORT_PASSWORD, choice.inviteToken) }.reason,
        )

        val words = a.auth.signUp(a.account.username, a.account.password, choice.inviteToken)

        assertEquals(RECOVERY_WORDS, words.size)
        val unlocked = a.unlocked()
        assertEquals(a.account.username, unlocked.username)
        assertTrue(a.auth.recoveryPending())
        assertEquals(words, a.auth.pendingRecoveryWords())
        a.auth.confirmRecoveryKeySaved()
        assertFalse(a.auth.recoveryPending())
        a.rememberUserId()
        a.syncUntilIdle()
        assertEquals(emptyList<String>(), a.entryIds())
        assertTrue(a.homeCollectionId().isNotEmpty())

        val b = signInDevice("B", a.account)
        assertEquals(a.userId, b.userId)
        val other = chosenDevice("C", newAccount())
        assertEquals(
            AuthFailure.INVITE_INVALID,
            authFailure { other.auth.signUp(other.account.username, other.account.password, invite.token) }.reason,
        )
    }

    companion object {
        private const val RECOVERY_WORDS = 24
        private const val SHORT_PASSWORD = "eleven char"

        @get:ClassRule
        @JvmStatic
        val harness = E2eServer(mapOf(E2eConstants.SIGNUP_MODE to E2eConstants.SIGNUP_INVITE))
    }
}
