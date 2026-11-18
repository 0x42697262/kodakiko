package dev.kodakiko.service.account

import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.WORDLIST_EN
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.StorageKeys
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewAdditionsTest : AccountTestBase() {
    private class LockedOffline(val sodium: TestSodium, val server: FakeAuthServer, val device: TestDevice, val app: TestApp)

    private suspend fun TestScope.lockedOffline(): LockedOffline {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        server.online = false
        val app = startApp(server, device, sodium)
        app.service.restore()
        server.online = true
        return LockedOffline(sodium, server, device, app)
    }

    @Test
    fun aRevokedSessionDuringTheKeyBundleRefreshForcesAFullSignIn() = runTest {
        val locked = lockedOffline()
        locked.server.revokeSessions()

        val failure = authFailure { locked.app.service.unlockWithPassword(NEW_PASSWORD) }
        assertEquals(AuthFailure.INVALID_CREDENTIALS, failure.reason)

        assertEquals(AccountState.SignedOut(USERNAME), locked.app.accountState.value)
        assertNull(locked.device.sessionStore.load(accountKeyOf(locked.server)))
    }

    @Test
    fun keepsTheCachedBundleWhenTheFreshBundleDoesNotOpenEither() = runTest {
        val locked = lockedOffline()
        val sodium = locked.sodium
        val stored = locked.server.user(USERNAME)
        val cached = locked.device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)
        val foreign = wrapKey(sodium, sodium.randomBytes(KEY_LENGTH), sodium.randomBytes(KEY_LENGTH), AdContext.MASTER_KEY_BY_KEK, stored.id)
        locked.server.replaceBundle(USERNAME, sodium.randomBytes(KEY_LENGTH), stored.bundle.copy(masterKeyByKek = encodeBytes(foreign)))

        val failure = authFailure { locked.app.service.unlockWithPassword("not the password at all") }
        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)

        assertArrayEquals(cached, locked.device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK))
    }

    @Test
    fun recoveryDecodesTheWordsLocallyAndSendsNoRequestForAnInvalidKey() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val words = signUpAlice(server, createDevice(), sodium).recoveryWords
        sodium.clearSecrets()
        val app = startApp(server, createDevice(), sodium)
        val swapped = words.toMutableList()
        swapped[swapped.lastIndex] = WORDLIST_EN[WORDLIST_EN.indexOf(swapped.last()) xor 1]

        val first = authFailure { app.service.recoverAccount(username = USERNAME, words = swapped.joinToString(" "), newPassword = NEW_PASSWORD) }
        assertEquals(AuthFailure.RECOVERY_WORDS_INVALID, first.reason)
        val second = authFailure { app.service.recoverAccount(username = USERNAME, words = words.drop(1).joinToString(" "), newPassword = NEW_PASSWORD) }
        assertEquals(AuthFailure.RECOVERY_WORDS_INVALID, second.reason)

        assertEquals(0, server.count(Routes.authRecoveryStart()))
    }

    @Test
    fun signOutFinishesEveryStepEvenWhenSomeOfThemFail() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = signUpAlice(server, device, sodium)
        app.inviteToken.value = UPPER_INVITE
        val accountKey = accountKeyOf(server)
        app.keyVault.forgetFailure = IllegalStateException("vault")
        device.rowStore.destroyFailure = IllegalStateException("rows")
        server.online = false

        app.service.signOut()

        assertTrue(device.fileStore.destroyCalls.contains(accountKey))
        assertNull(device.sessionStore.load(accountKey))
        assertNull(app.activeAccountKey.value)
        assertNull(device.globalStore.activeAccount())
        assertEquals(AccountState.SignedOut(), app.accountState.value)
        assertNull(app.inviteToken.value)
    }

    @Test
    fun forgetsTheInviteTokenAfterASuccessfulSignUp() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val app = startApp(server, createDevice(), sodium)
        app.inviteToken.value = UPPER_INVITE
        app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
        assertNull(app.inviteToken.value)
        assertEquals(listOf<String?>(null), server.signUpInvites.toList())
    }

    @Test
    fun recoveryPendingReadsOnlyTheConfirmedFlagAndCountsAnErrorAsPending() = runTest {
        val sodium = TestSodium()
        val device = createDevice()
        val app = signUpAlice(createFakeAuthServer(), device, sodium)
        assertTrue(app.service.recoveryPending())

        device.rowStore.nextKvGetJsonFailure = IllegalStateException("unreadable")
        assertTrue(app.service.recoveryPending())

        app.service.confirmRecoveryKeySaved()
        assertEquals(false, app.service.recoveryPending())

        device.rowStore.kvDelete(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED)
        assertEquals(false, app.service.recoveryPending())
    }
}
