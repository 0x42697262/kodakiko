package dev.kodakiko.service.account

import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.data.db.StorageKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsAdditionsTest : AccountTestBase() {
    @Test
    fun changePasswordSendsTheNewMaterialReplacesTheSessionAndStoresTheNewParameters() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = signUpAlice(server, device, sodium)
        val accountKey = accountKeyOf(server)
        val before = device.sessionStore.load(accountKey)
        val loginBefore = server.user(USERNAME).loginKey
        val wrapBefore = device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)

        app.service.changePassword(PASSWORD, NEW_PASSWORD)

        assertNotEquals(loginBefore, server.user(USERNAME).loginKey)
        assertNotEquals(before, device.sessionStore.load(accountKey))
        assertNotNull(device.sessionStore.load(accountKey))
        assertFalse(wrapBefore.contentEquals(device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK)))
        assertTrue(app.service.verifyPassword(NEW_PASSWORD))
        assertFalse(app.service.verifyPassword(PASSWORD))
    }

    @Test
    fun aWrongCurrentPasswordSendsNoRequest() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = signUpAlice(server, device, sodium)
        val failure = authFailure { app.service.changePassword("wrong password entirely", NEW_PASSWORD) }
        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)
        assertEquals(0, server.count(Routes.authPassword()))
    }

    @Test
    fun aNewPasswordThatBreaksThePolicySendsNoRequest() = runTest {
        val server = createFakeAuthServer()
        val sodium = TestSodium()
        val app = signUpAlice(server, createDevice(), sodium)
        val failure = authFailure { app.service.changePassword(PASSWORD, "short") }
        assertEquals(AuthFailure.PASSWORD_POLICY, failure.reason)
        assertEquals(0, server.count(Routes.authPassword()))
    }

    @Test
    fun revealRecoveryWordsReturnsTheSignUpWordsForTheRightPasswordOnly() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = signUpAlice(server, device, sodium)
        assertEquals(app.recoveryWords, app.service.revealRecoveryWords(PASSWORD))
        val failure = authFailure { app.service.revealRecoveryWords("nope nope nope nope") }
        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)
    }

    @Test
    fun revealRecoveryWordsWipesTheOpenedKeys() = runTest {
        val server = createFakeAuthServer()
        val sodium = TestSodium()
        val app = signUpAlice(server, createDevice(), sodium)
        sodium.clearSecrets()
        app.service.revealRecoveryWords(PASSWORD)
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun requestAccountDeletionSignsOutKeepsLocalDataAndRemembersTheDateForSignIn() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = signUpAlice(server, device, sodium)
        val accountKey = accountKeyOf(server)
        val deletionDueAt = app.service.requestAccountDeletion()
        assertEquals(AccountState.SignedOut(USERNAME), app.accountState.value)
        assertNull(device.sessionStore.load(accountKey))
        assertEquals(deletionDueAt, app.service.deletionScheduledFor())
        assertEquals(UserStatus.PENDING_DELETION, server.user(USERNAME).status)
        assertEquals(JsonPrimitive(deletionDueAt), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_DELETION_DUE_AT))
        assertTrue(device.databaseFile(accountKey).isFile)
        assertTrue(device.rowStore.destroyCalls.isEmpty())
    }

    @Test
    fun changeServerSignsOutAndForgetsTheServer() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = signUpAlice(server, device, sodium)
        device.globalStore.setServerConfigJson(configJson(config()))
        app.service.changeServer()
        assertEquals(1, app.serverUrl.clearCalls)
        assertEquals(AccountState.NoServer, app.accountState.value)
        assertNull(device.globalStore.serverConfigJson())
        assertEquals(1, server.count(Routes.authSignOut()))
        assertNull(app.activeAccountKey.value)
    }

    @Test
    fun changeServerForgetsAScheduledDeletionNotice() = runTest {
        val server = createFakeAuthServer()
        val sodium = TestSodium()
        val app = signUpAlice(server, createDevice(), sodium)
        app.service.requestAccountDeletion()
        app.service.changeServer()
        assertNull(app.service.deletionScheduledFor())
    }
}
