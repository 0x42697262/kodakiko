package dev.kodakiko.service.account

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalModeTest : AccountTestBase() {
    private val chosenConfig = config(
        sharingEnabled = true,
        publicLinksEnabled = true,
        maxMediaPerEntry = 20,
        maxUploadBytes = 1000,
        defaultQuotaBytes = 0,
        trashDays = 30,
        accountDeletionGraceDays = 7,
        serverVersion = "0.1.0",
    )

    private suspend fun refusedConnect(app: TestApp, choice: ServerChoiceFailure): AuthFailure {
        app.serverUrl.chooseResult = ServerChoice.Failed(choice)
        return authFailure {
            app.service.connectServer(serverInput = "x", inviteToken = null, username = USERNAME, password = PASSWORD)
        }.reason
    }

    @Test
    fun setUpLocalSendsNoRequestStoresTheRecoveryWrapAndTheLocalModeAndEndsUnlocked() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = localAlice(server, device, sodium)
        assertEquals(0, server.calls.size)
        assertEquals(WORDS_PER_KEY, app.recoveryWords.size)
        assertTrue(app.accountKey().startsWith("local/"))
        assertEquals(JsonPrimitive("local"), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_MODE))
        assertNotNull(device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_RECOVERY))
        assertEquals(AccountMode.LOCAL, app.service.mode.value)
        val state = app.unlocked()
        assertEquals(USERNAME, state.username)
        assertEquals(ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, app.accountKey(), USERNAME, AccountMode.LOCAL), device.globalStore.activeAccount())
        assertTrue(app.keyVault.storeCalls.contains(app.accountKey()))
        assertEquals(JsonPrimitive(false), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED))
    }

    @Test
    fun setUpLocalWritesTheHomeCollectionRow() = runTest {
        val device = createDevice()
        val sodium = TestSodium()
        val app = localAlice(createFakeAuthServer(), device, sodium)
        val homeId = (device.rowStore.kvGetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID) as JsonPrimitive).content
        val home = device.rowStore.transaction { it.collections.get(homeId) } ?: throw AssertionError("expected the home collection")
        assertEquals(CollectionKind.HOME, home.kind)
        assertEquals(CollectionRole.OWNER, home.role)
        assertEquals(app.unlocked().userId, home.ownerId)
        assertEquals(USERNAME, home.ownerUsername)
        assertEquals(EPOCH_ISO, home.metaUpdatedAt)
        assertEquals(0L, home.serverLastSeq)
        assertEquals(0L, home.cursorSeq)
    }

    @Test
    fun setUpLocalRefusesAnInvalidUsernameBeforeTheSecuringScreen() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium, noServer = true)
        val failure = authFailure { app.service.setUpLocal(username = "no spaces allowed", password = PASSWORD) }
        assertEquals(AuthFailure.USERNAME_INVALID, failure.reason)
        assertEquals(0, app.showSecuringCalls)
        assertEquals(0, sodium.argon2Calls)
    }

    @Test
    fun setUpLocalLeavesOnlyTheAccountKeysUnwipedAfterSuccess() = runTest {
        val sodium = TestSodium()
        val app = localAlice(createFakeAuthServer(), createDevice(), sodium)
        sodium.assertWiped(app.liveKeys())
    }

    @Test
    fun restoresWithoutAServerUnlocksWithThePasswordAndNeverFetchesAKeyBundle() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        localAlice(server, device, sodium)
        val again = startApp(server, device, sodium, noServer = true)
        again.service.restore()
        assertEquals(AccountState.Locked, again.accountState.value)
        assertEquals(AccountMode.LOCAL, again.service.mode.value)
        val failure = authFailure { again.service.unlockWithPassword("wrong password entirely") }
        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)
        again.service.unlockWithPassword(PASSWORD)
        assertTrue(again.accountState.value is AccountState.Unlocked)
        assertEquals(0, server.calls.size)
    }

    @Test
    fun localPasswordChangeAndLocalRecoveryChangeOnlyTheStoredWrapAndKeepTheMasterKey() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = localAlice(server, device, sodium)
        val masterKey = app.unlocked().keys.masterKey.copyOf()
        app.service.changePassword(PASSWORD, NEW_PASSWORD)
        assertTrue(app.service.verifyPassword(NEW_PASSWORD))
        assertFalse(app.service.verifyPassword(PASSWORD))
        app.service.recoverAccount(username = USERNAME, words = app.recoveryWords.joinToString(" "), newPassword = PASSWORD)
        assertTrue(app.service.verifyPassword(PASSWORD))
        assertArrayEquals(masterKey, app.unlocked().keys.masterKey)
        assertEquals(0, server.calls.size)
        assertEquals(JsonPrimitive(true), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED))
    }

    @Test
    fun connectServerRegistersTheStoredKeysAndIdsAndSwitchesToServerMode() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = localAlice(server, device, sodium)
        val accountKey = app.accountKey()
        val userId = app.unlocked().userId
        val wrapBefore = device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK) ?: ByteArray(0)
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, chosenConfig, null)
        app.service.connectServer(serverInput = BASE_URL, inviteToken = VALID_INVITE, username = USERNAME, password = PASSWORD)
        val registered = server.user(USERNAME)
        assertEquals(userId, registered.id)
        assertEquals(encodeBytes(wrapBefore), registered.bundle.masterKeyByKek)
        assertEquals(AccountMode.SERVER, app.service.mode.value)
        assertEquals(JsonPrimitive("server"), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_MODE))
        assertEquals(accountKey, app.activeAccountKey.value)
        assertNotNull(device.sessionStore.load(accountKey))
        assertEquals(listOf(chosenConfig), app.config.accepted.toList())
        assertEquals(ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, accountKey, USERNAME, AccountMode.SERVER), device.globalStore.activeAccount())
        assertEquals(JsonPrimitive("member"), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_ROLE))
        assertEquals(listOf(BASE_URL), app.serverUrl.chosen.toList())
        assertEquals(listOf<String?>(VALID_INVITE), server.signUpInvites.toList())
        val relogin = startApp(server, createDevice(), sodium)
        relogin.service.signIn(username = USERNAME, password = PASSWORD)
        assertEquals(userId, relogin.unlocked().userId)
    }

    @Test
    fun connectServerUsesTheInviteTokenOfTheChosenServerWhenNoneWasTyped() = runTest {
        val server = createFakeAuthServer()
        val sodium = TestSodium()
        val app = localAlice(server, createDevice(), sodium)
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, chosenConfig, VALID_INVITE)
        app.service.connectServer(serverInput = BASE_URL, inviteToken = null, username = USERNAME, password = PASSWORD)
        assertEquals(AccountMode.SERVER, app.service.mode.value)
        assertEquals(app.unlocked().userId, server.user(USERNAME).id)
        assertEquals(listOf<String?>(VALID_INVITE), server.signUpInvites.toList())
    }

    @Test
    fun aTakenUsernameLeavesTheAccountLocal() = runTest {
        val server = createFakeAuthServer()
        val sodium = TestSodium()
        signUpAlice(server, createDevice(), sodium)
        val device = createDevice()
        val app = localAlice(server, device, sodium)
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, chosenConfig, null)
        val failure = authFailure {
            app.service.connectServer(serverInput = BASE_URL, inviteToken = null, username = USERNAME, password = PASSWORD)
        }
        assertEquals(AuthFailure.USERNAME_TAKEN, failure.reason)
        assertEquals(AccountMode.LOCAL, app.service.mode.value)
        assertEquals(JsonPrimitive("local"), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_MODE))
        assertEquals(1, app.serverUrl.clearCalls)
        assertEquals(emptyList<Any>(), app.config.accepted.toList())
    }

    @Test
    fun aRefusedServerChoiceLeavesTheAccountLocalWithoutARequest() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = localAlice(server, device, sodium)
        assertEquals(AuthFailure.NOT_HTTPS, refusedConnect(app, ServerChoiceFailure.NOT_HTTPS))
        assertEquals(AuthFailure.UNREACHABLE, refusedConnect(app, ServerChoiceFailure.UNREACHABLE))
        assertEquals(AuthFailure.NOT_KODAKIKO, refusedConnect(app, ServerChoiceFailure.NOT_KODAKIKO))
        assertEquals(AuthFailure.NOT_KODAKIKO, refusedConnect(app, ServerChoiceFailure.INVALID))
        assertEquals(0, app.serverUrl.clearCalls)
        assertEquals(AccountMode.LOCAL, app.service.mode.value)
        assertEquals(0, server.calls.size)
    }

    @Test
    fun connectServerWithAWrongPasswordSendsNoRequestAndStaysLocal() = runTest {
        val server = createFakeAuthServer()
        val sodium = TestSodium()
        val app = localAlice(server, createDevice(), sodium)
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, chosenConfig, null)
        val failure = authFailure {
            app.service.connectServer(serverInput = BASE_URL, inviteToken = null, username = USERNAME, password = NEW_PASSWORD)
        }
        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)
        assertEquals(AccountMode.LOCAL, app.service.mode.value)
        assertEquals(0, server.calls.size)
        assertEquals(1, app.serverUrl.clearCalls)
    }

    @Test
    fun eraseLocalDestroysTheStoresAndForgetsTheAccount() = runTest {
        val server = createFakeAuthServer()
        val device = createDevice()
        val sodium = TestSodium()
        val app = localAlice(server, device, sodium)
        val accountKey = app.accountKey()
        app.service.eraseLocal()
        assertTrue(device.rowStore.destroyCalls.contains(accountKey))
        assertNull(device.globalStore.activeAccount())
        assertEquals(AccountState.NoServer, app.accountState.value)
        assertEquals(0, server.calls.size)
        assertTrue(device.fileStore.destroyCalls.contains(accountKey))
        assertTrue(app.keyVault.forgetCalls.contains(accountKey))
        assertNull(app.activeAccountKey.value)
        assertEquals(AccountMode.SERVER, app.service.mode.value)
        assertFalse(device.databaseFile(accountKey).exists())
    }

    @Test
    fun eraseLocalWipesTheUnlockedKeysAndRunsBeforeSignOut() = runTest {
        val sodium = TestSodium()
        val app = localAlice(createFakeAuthServer(), createDevice(), sodium)
        val keys: AccountKeys = app.unlocked().keys
        var hookCalls = 0
        app.beforeSignOut = { hookCalls += 1 }
        app.service.eraseLocal()
        assertEquals(1, hookCalls)
        assertTrue(keys.masterKey.all { it == 0.toByte() })
        assertTrue(keys.privateKey.all { it == 0.toByte() })
    }
}
