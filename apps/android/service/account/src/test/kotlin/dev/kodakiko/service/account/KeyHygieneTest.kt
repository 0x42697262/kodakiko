package dev.kodakiko.service.account

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.wrapKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyHygieneTest : AccountTestBase() {
    private class Account(val sodium: TestSodium, val server: FakeAuthServer, val words: List<String>)

    private suspend fun TestScope.accountWithSecrets(): Account {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val first = signUpAlice(server, createDevice(), sodium)
        sodium.clearSecrets()
        return Account(sodium, server, first.recoveryWords)
    }

    @Test
    fun signInLeavesOnlyTheAccountKeysUnwipedAfterSuccess() = runTest {
        val account = accountWithSecrets()
        val app = startApp(account.server, createDevice(), account.sodium)
        app.service.signIn(username = USERNAME, password = PASSWORD)
        account.sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun signInWipesTheKekAndLoginKeyWhenTheCredentialsAreRefused() = runTest {
        val account = accountWithSecrets()
        val app = startApp(account.server, createDevice(), account.sodium)
        val failure = authFailure { app.service.signIn(username = USERNAME, password = NEW_PASSWORD) }
        assertEquals(AuthFailure.INVALID_CREDENTIALS, failure.reason)
        account.sodium.assertWiped()
    }

    @Test
    fun signInWipesEverythingWhenTheKeyBundleDoesNotOpen() = runTest {
        val account = accountWithSecrets()
        val sodium = account.sodium
        val stored = account.server.user(USERNAME)
        val foreign = wrapKey(sodium, sodium.randomBytes(KEY_LENGTH), sodium.randomBytes(KEY_LENGTH), AdContext.MASTER_KEY_BY_KEK, stored.id)
        account.server.replaceBundle(USERNAME, null, stored.bundle.copy(masterKeyByKek = encodeBytes(foreign)))
        sodium.clearSecrets()
        val app = startApp(account.server, createDevice(), sodium)

        val failure = authFailure { app.service.signIn(username = USERNAME, password = PASSWORD) }
        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)
        sodium.assertWiped()
    }

    @Test
    fun signInWipesTheOpenedAccountKeysWhenSavingThemFails() = runTest {
        val account = accountWithSecrets()
        val device = createDevice()
        device.rowStore.nextKvSetJsonFailure = IllegalStateException("disk full")
        val app = startApp(account.server, device, account.sodium)

        val failure = anyFailure { app.service.signIn(username = USERNAME, password = PASSWORD) }
        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure.message)
        account.sodium.assertWiped()
    }

    @Test
    fun signUpLeavesOnlyTheAccountKeysUnwipedAfterSuccess() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium)
        app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun signUpWipesEverySecretWhenTheServerRefusesIt() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        server.failOnce(Routes.authSignUp(), 409, ErrorCode.USERNAME_TAKEN)
        val app = startApp(server, createDevice(), sodium)
        val failure = authFailure { app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null) }
        assertEquals(AuthFailure.USERNAME_TAKEN, failure.reason)
        sodium.assertWiped()
        assertEquals(1, app.dismissSecuringCalls)
    }

    @Test
    fun signUpWipesEverySecretWhenSavingTheAccountFails() = runTest {
        val sodium = TestSodium()
        val device = createDevice()
        device.rowStore.nextKvSetJsonFailure = IllegalStateException("disk full")
        val app = startApp(createFakeAuthServer(), device, sodium)
        val failure = anyFailure { app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null) }
        assertTrue(failure is IllegalStateException)
        assertEquals("disk full", failure.message)
        sodium.assertWiped()
    }

    @Test
    fun recoveryLeavesOnlyTheAccountKeysUnwipedAfterSuccess() = runTest {
        val account = accountWithSecrets()
        val app = startApp(account.server, createDevice(), account.sodium)
        app.service.recoverAccount(username = USERNAME, words = account.words.joinToString(" "), newPassword = NEW_PASSWORD)
        account.sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun recoveryWipesEverySecretWhenTheServerRefusesTheCompletion() = runTest {
        val account = accountWithSecrets()
        account.server.failOnce(Routes.authRecoveryComplete(), 500, ErrorCode.INTERNAL)
        val app = startApp(account.server, createDevice(), account.sodium)
        val failure = authFailure { app.service.recoverAccount(username = USERNAME, words = account.words.joinToString(" "), newPassword = NEW_PASSWORD) }
        assertEquals(AuthFailure.GENERIC, failure.reason)
        account.sodium.assertWiped()
    }

    @Test
    fun recoveryWipesTheOpenedKeysWhenTheNewPasswordBreaksThePolicy() = runTest {
        val account = accountWithSecrets()
        val app = startApp(account.server, createDevice(), account.sodium)
        val failure = authFailure { app.service.recoverAccount(username = USERNAME, words = account.words.joinToString(" "), newPassword = "short") }
        assertEquals(AuthFailure.PASSWORD_POLICY, failure.reason)
        account.sodium.assertWiped()
        assertEquals(0, account.server.count(Routes.authRecoveryComplete()))
    }

    @Test
    fun unlockWithThePasswordWipesTheKekAndLoginKey() = runTest {
        val account = accountWithSecrets()
        val device = createDevice()
        val first = startApp(account.server, device, account.sodium)
        first.service.signIn(username = USERNAME, password = PASSWORD)
        val restarted = startApp(account.server, device, account.sodium)
        account.server.online = false
        restarted.service.restore()
        account.sodium.clearSecrets()
        restarted.service.unlockWithPassword(PASSWORD)
        account.sodium.assertWiped(restarted.liveKeys())
    }

    @Test
    fun unlockWithTheDeviceWipesAMasterKeyThatDoesNotOpenTheAccount() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val wrongKey = sodium.randomBytes(KEY_LENGTH)
        val vault = MemoryKeyVault(loadOverride = { wrongKey })
        val first = startApp(server, device, sodium, keyVault = vault)
        first.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)

        val restarted = startApp(server, device, sodium, keyVault = vault)
        restarted.service.restore()

        assertEquals(AccountState.Locked, restarted.accountState.value)
        assertTrue(wrongKey.all { it == 0.toByte() })
    }

    @Test
    fun pendingRecoveryWordsWipesTheUnwrappedRecoveryKey() = runTest {
        val sodium = TestSodium()
        val app = signUpAlice(createFakeAuthServer(), createDevice(), sodium)
        sodium.clearSecrets()
        assertEquals(app.recoveryWords, app.service.pendingRecoveryWords())
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun changePasswordLeavesOnlyTheAccountKeysUnwiped() = runTest {
        val sodium = TestSodium()
        val app = signUpAlice(createFakeAuthServer(), createDevice(), sodium)
        sodium.clearSecrets()
        app.service.changePassword(PASSWORD, NEW_PASSWORD)
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun verifyPasswordWipesTheOpenedKeysForTheRightAndTheWrongPassword() = runTest {
        val sodium = TestSodium()
        val app = signUpAlice(createFakeAuthServer(), createDevice(), sodium)
        sodium.clearSecrets()
        assertTrue(app.service.verifyPassword(PASSWORD))
        sodium.assertWiped(app.liveKeys())
        sodium.clearSecrets()
        assertFalse(app.service.verifyPassword(NEW_PASSWORD))
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun localRecoveryLeavesOnlyTheNewAccountKeysUnwiped() = runTest {
        val sodium = TestSodium()
        val app = localAlice(createFakeAuthServer(), createDevice(), sodium)
        val previous: AccountKeys = app.unlocked().keys
        sodium.clearSecrets()
        app.service.recoverAccount(username = USERNAME, words = app.recoveryWords.joinToString(" "), newPassword = NEW_PASSWORD)
        sodium.assertWiped(app.liveKeys())
        assertTrue(previous.masterKey.all { it == 0.toByte() })
        assertTrue(previous.privateKey.all { it == 0.toByte() })
    }

    @Test
    fun connectServerWipesTheRebuiltLoginKeys() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val app = localAlice(server, createDevice(), sodium)
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, config(), null)
        sodium.clearSecrets()
        app.service.connectServer(serverInput = BASE_URL, inviteToken = null, username = USERNAME, password = PASSWORD)
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun lockingFromOutsideWipesTheUnlockedKeys() = runTest {
        val sodium = TestSodium()
        val app = signUpAlice(createFakeAuthServer(), createDevice(), sodium)
        val keys: AccountKeys = app.unlocked().keys
        app.accountState.value = AccountState.Locked
        assertTrue(keys.masterKey.all { it == 0.toByte() })
        assertTrue(keys.privateKey.all { it == 0.toByte() })
    }

    @Test
    fun replacingTheUnlockedStateWithTheSameKeysKeepsThem() = runTest {
        val sodium = TestSodium()
        val app = signUpAlice(createFakeAuthServer(), createDevice(), sodium)
        val state = app.unlocked()
        val keys: AccountKeys = state.keys
        app.accountState.value = AccountState.Unlocked(keys = keys, userId = state.userId, username = "renamed")
        assertTrue(keys.masterKey.any { it != 0.toByte() })
        assertTrue(keys.privateKey.any { it != 0.toByte() })
    }
}
