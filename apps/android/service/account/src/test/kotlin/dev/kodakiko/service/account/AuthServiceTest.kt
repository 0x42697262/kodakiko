package dev.kodakiko.service.account

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.account.deriveLoginMaterial
import dev.kodakiko.account.openKeyBundle
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.StorageKeys
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
class AuthServiceTest : AccountTestBase() {
    @Test
    fun signUpStoresTheKvDataAndTheSessionAndLeavesTheRecoveryKeyUnconfirmed() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = signUpAlice(server, device, sodium)
        val accountKey = accountKeyOf(server)

        assertEquals(24, app.recoveryWords.size)
        assertTrue(device.sessionStore.load(accountKey).orEmpty().matches(Regex(SESSION_TOKEN_PATTERN)))
        assertEquals(JsonPrimitive(server.user(USERNAME).id), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_USER_ID))
        assertEquals(JsonPrimitive(USERNAME), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_USERNAME))
        assertEquals(JsonPrimitive(false), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED))
        assertNotNull(device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK))
        assertNotNull(device.rowStore.kvGet(StorageKeys.ACCOUNT_RECOVERY_KEY_BY_MASTER))
        assertNotNull(device.rowStore.kvGet(StorageKeys.ACCOUNT_PRIVATE_KEY_BY_MASTER))
        assertNotNull(device.rowStore.kvGet(StorageKeys.ACCOUNT_PUBLIC_KEY))
        val homeCollectionId = device.rowStore.kvGetJson(StorageKeys.ACCOUNT_HOME_COLLECTION_ID) as? JsonPrimitive
        assertTrue(homeCollectionId?.isString == true)
        assertTrue(app.accountState.value is AccountState.Unlocked)
        assertEquals(accountKey, app.activeAccountKey.value)
        assertTrue(device.databaseFile(accountKey).isFile)
        assertEquals(listOf<String?>(null), server.signUpInvites.toList())
    }

    @Test
    fun signUpSendsTheTypedInviteToken() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val app = startApp(server, createDevice(), sodium)
        app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = VALID_INVITE)
        assertEquals(listOf<String?>(VALID_INVITE), server.signUpInvites.toList())
    }

    @Test
    fun signUpAndSignInNormalizeTheUsername() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val first = startApp(server, createDevice(), sodium)
        first.service.signUp(username = "  Alice ", password = PASSWORD, inviteToken = null)
        assertEquals(USERNAME, server.user(USERNAME).username)
        assertEquals(USERNAME, first.unlocked().username)
        val second = startApp(server, createDevice(), sodium)
        second.service.signIn(username = "ALICE", password = PASSWORD)
        assertEquals(USERNAME, second.unlocked().username)
    }

    @Test
    fun signUpRefusesAnInvalidUsernameBeforeTheSecuringScreen() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val app = startApp(server, createDevice(), sodium)
        val failure = authFailure { app.service.signUp(username = "no spaces allowed", password = PASSWORD, inviteToken = null) }
        assertEquals(AuthFailure.USERNAME_INVALID, failure.reason)
        assertEquals(0, app.showSecuringCalls)
        assertEquals(0, server.calls.size)
    }

    @Test
    fun theAccountKeyKeepsANonDefaultPortOfTheServerUrl() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = startApp(server, device, sodium, baseUrl = "$BASE_URL:$NON_DEFAULT_PORT")
        app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
        val accountKey = "$HOST:$NON_DEFAULT_PORT/${server.user(USERNAME).id}"
        assertEquals(accountKey, app.activeAccountKey.value)
        assertEquals(accountKey, device.globalStore.activeAccount()?.accountKey)
        assertTrue(device.databaseFile(accountKey).isFile)
    }

    @Test
    fun showsTheSecuringScreenBeforeTheKeyDerivationAndAfterThePolicyCheck() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = startApp(server, device, sodium)
        val argonCallsAtSecuring = mutableListOf<Int>()
        val argonCallsAfterInteractions = mutableListOf<Int>()
        val securingCallsAfterInteractions = mutableListOf<Int>()
        app.onShowSecuring = { argonCallsAtSecuring.add(sodium.argon2Calls) }
        app.lifecycle.onAfterInteractions = {
            argonCallsAfterInteractions.add(sodium.argon2Calls)
            securingCallsAfterInteractions.add(app.showSecuringCalls)
        }

        val failure = authFailure { app.service.signUp(username = USERNAME, password = "short", inviteToken = null) }
        assertEquals(AuthFailure.PASSWORD_POLICY, failure.reason)
        assertEquals(listOf(PasswordRule.TOO_SHORT), failure.rules)
        assertEquals(0, app.showSecuringCalls)
        assertEquals(0, app.lifecycle.afterInteractionsCalls)

        app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
        assertEquals(1, app.showSecuringCalls)
        assertEquals(listOf(0), argonCallsAtSecuring)
        assertEquals(listOf(0), argonCallsAfterInteractions)
        assertEquals(listOf(1), securingCallsAfterInteractions)
    }

    @Test
    fun restoreWithA401ClearsTheTokenAndEndsSignedOut() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        server.revokeSessions()

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()

        assertTrue(restarted.accountState.value is AccountState.SignedOut)
        assertNull(device.sessionStore.load(accountKeyOf(server)))
    }

    @Test
    fun restoreWithoutAnActiveAccountEndsSignedOut() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium)
        app.service.restore()
        assertTrue(app.accountState.value is AccountState.SignedOut)
    }

    @Test
    fun restoreWithoutAServerEndsInTheNoServerState() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium, noServer = true)
        app.service.restore()
        assertEquals(AccountState.NoServer, app.accountState.value)
    }

    @Test
    fun restoreOfflineWithAStoredSessionOpensTheStoresAndEndsLocked() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        server.online = false

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()

        assertEquals(AccountState.Locked, restarted.accountState.value)
        assertEquals(accountKeyOf(server), restarted.activeAccountKey.value)
    }

    @Test
    fun restoreReportsAPendingDeletion() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        server.markPendingDeletion(USERNAME)

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()
        assertEquals(AccountState.PendingDeletion, restarted.accountState.value)
        assertEquals(JsonPrimitive(ONE_DAY_ISO), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_DELETION_DUE_AT))
    }

    @Test
    fun restoreUnlocksWithTheDeviceKeyWhenTheKeyVaultIsPersistent() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val persistent = MemoryKeyVault()
        val first = startApp(server, device, sodium, keyVault = persistent)
        first.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)

        val restarted = startApp(server, device, sodium, keyVault = persistent)
        restarted.service.restore()
        assertTrue(restarted.accountState.value is AccountState.Unlocked)
    }

    @Test
    fun unlockWithDeviceReportsWhetherTheVaultHeldAKeyForTheActiveAccount() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val first = startApp(server, device, sodium)
        assertFalse(first.service.unlockWithDevice())
        first.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
        val masterKey = first.unlocked().keys.masterKey.copyOf()
        server.online = false

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()
        assertFalse(restarted.service.unlockWithDevice())
        assertEquals(AccountState.Locked, restarted.accountState.value)
        restarted.keyVault.storeMasterKey(accountKeyOf(server), masterKey)
        assertTrue(restarted.service.unlockWithDevice())
        assertArrayEquals(masterKey, restarted.unlocked().keys.masterKey)
    }

    @Test
    fun unlocksWithTheRightPassword() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        server.online = false

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()
        restarted.service.unlockWithPassword(PASSWORD)

        assertTrue(restarted.accountState.value is AccountState.Unlocked)
        assertEquals(0, server.count(Routes.authKeyBundle()))
    }

    @Test
    fun fetchesTheKeyBundleOnceAfterAWrongPasswordAndThenReportsWrongPassword() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()
        val failure = authFailure { restarted.service.unlockWithPassword("not the password at all") }

        assertEquals(AuthFailure.WRONG_PASSWORD, failure.reason)
        assertEquals(1, server.count(Routes.authKeyBundle()))
        assertEquals(AccountState.Locked, restarted.accountState.value)
    }

    @Test
    fun replacesAStaleCachedWrapWithTheFreshKeyBundleAndUnlocks() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        val stored = server.user(USERNAME)
        val userId = stored.id
        val oldMaterial = deriveLoginMaterial(sodium, PASSWORD, floorParams(decodeBytes(stored.bundle.salt)))
        val opened: AccountKeys = openKeyBundle(sodium, oldMaterial.kek, userId, bundleBytes(stored.bundle))
        val newSalt = sodium.randomBytes(SALT_LENGTH)
        val newMaterial = deriveLoginMaterial(sodium, NEW_PASSWORD, floorParams(newSalt))
        val newWrap = wrapKey(sodium, newMaterial.kek, opened.masterKey, AdContext.MASTER_KEY_BY_KEK, userId)
        server.replaceBundle(
            USERNAME,
            newMaterial.loginKey,
            stored.bundle.copy(salt = encodeBytes(newSalt), masterKeyByKek = encodeBytes(newWrap)),
        )

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()
        restarted.service.unlockWithPassword(NEW_PASSWORD)

        assertTrue(restarted.accountState.value is AccountState.Unlocked)
        assertEquals(1, server.count(Routes.authKeyBundle()))
        assertArrayEquals(newWrap, device.rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_KEK))
    }

    @Test
    fun refusesHostileParametersBeforeAnyArgon2idCall() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        signUpAlice(server, device, sodium)
        val callsBefore = sodium.argon2Calls
        server.hostileParams = true

        val app = startApp(server, createDevice(), sodium)
        val failure = authFailure { app.service.signIn(username = USERNAME, password = PASSWORD) }

        assertEquals(AuthFailure.UNSAFE_SERVER, failure.reason)
        assertEquals(callsBefore, sodium.argon2Calls)
    }

    @Test
    fun signsInWithTheRightPasswordAndRejectsWrongCredentialsWithOneAnswer() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        signUpAlice(server, createDevice(), sodium)

        val wrong = startApp(server, createDevice(), sodium)
        val wrongFailure = authFailure { wrong.service.signIn(username = USERNAME, password = NEW_PASSWORD) }
        val unknown = startApp(server, createDevice(), sodium)
        val unknownFailure = authFailure { unknown.service.signIn(username = "nobody-here", password = PASSWORD) }
        assertEquals(AuthFailure.INVALID_CREDENTIALS, wrongFailure.reason)
        assertEquals(AuthFailure.INVALID_CREDENTIALS, unknownFailure.reason)

        val right = startApp(server, createDevice(), sodium)
        right.service.signIn(username = USERNAME, password = PASSWORD)
        assertTrue(right.accountState.value is AccountState.Unlocked)
    }

    @Test
    fun signsInToAnAccountWithAPendingDeletionAndReportsIt() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        signUpAlice(server, createDevice(), sodium)
        server.requestDeletion(USERNAME)

        val app = startApp(server, createDevice(), sodium)
        app.service.signIn(username = USERNAME, password = PASSWORD)
        assertEquals(AccountState.PendingDeletion, app.accountState.value)
    }

    @Test
    fun showsTheRecoveryWordsAgainAfterARestartAndAnUnlockUntilConfirmed() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val first = signUpAlice(server, device, sodium)

        val restarted = startApp(server, device, sodium)
        restarted.service.restore()
        restarted.service.unlockWithPassword(PASSWORD)
        assertEquals(first.recoveryWords, restarted.service.pendingRecoveryWords())

        restarted.service.confirmRecoveryKeySaved()
        assertNull(restarted.service.pendingRecoveryWords())
        assertEquals(JsonPrimitive(true), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED))
    }

    @Test
    fun confirmingTheRecoveryKeyNotifiesTheUnlockedStateAgain() = runTest {
        val sodium = TestSodium()
        val app = signUpAlice(createFakeAuthServer(), createDevice(), sodium)
        val before = app.unlocked()
        val keys: AccountKeys = before.keys
        var calls = 0
        app.accountState.subscribe { calls += 1 }
        app.service.confirmRecoveryKeySaved()
        assertEquals(1, calls)
        assertTrue(app.accountState.value === before)
        assertFalse(keys.masterKey.all { it == 0.toByte() })
    }

    @Test
    fun recoversWithThe24WordsAndANewPassword() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val recoveryWords = signUpAlice(server, createDevice(), sodium).recoveryWords
        val device = createDevice()
        val app = startApp(server, device, sodium)

        app.service.recoverAccount(username = USERNAME, words = recoveryWords.joinToString("  ").uppercase(), newPassword = NEW_PASSWORD)

        assertTrue(app.accountState.value is AccountState.Unlocked)
        assertEquals(JsonPrimitive(true), device.rowStore.kvGetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED))
        assertTrue(device.sessionStore.load(accountKeyOf(server)).orEmpty().matches(Regex(SESSION_TOKEN_PATTERN)))

        val second = startApp(server, createDevice(), sodium)
        second.service.signIn(username = USERNAME, password = NEW_PASSWORD)
        assertTrue(second.accountState.value is AccountState.Unlocked)
        val third = startApp(server, createDevice(), sodium)
        val oldFailure = authFailure { third.service.signIn(username = USERNAME, password = PASSWORD) }
        assertEquals(AuthFailure.INVALID_CREDENTIALS, oldFailure.reason)
    }

    @Test
    fun reportsTheWordPositionWhenARecoveryWordIsNotInTheList() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val recoveryWords = signUpAlice(server, createDevice(), sodium).recoveryWords
        val app = startApp(server, createDevice(), sodium)
        val broken = recoveryWords.mapIndexed { index, word -> if (index == 4) "zzzzzz" else word }

        val failure = authFailure { app.service.recoverAccount(username = USERNAME, words = broken.joinToString(" "), newPassword = NEW_PASSWORD) }

        assertEquals(AuthFailure.RECOVERY_WORDS_INVALID, failure.reason)
        assertEquals(5, failure.position)
    }

    @Test
    fun cancelsAPendingDeletionAndUnlocksAgain() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        signUpAlice(server, createDevice(), sodium)
        server.requestDeletion(USERNAME)
        val app = startApp(server, createDevice(), sodium)
        app.service.signIn(username = USERNAME, password = PASSWORD)

        app.service.cancelDeletion()

        assertTrue(app.accountState.value is AccountState.Unlocked)
        assertEquals(1, server.count(Routes.usersMeDeleteCancel()))
    }

    @Test
    fun signOutWipesTheKeysInMemoryAndDestroysTheStores() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = signUpAlice(server, device, sodium)
        val keys: AccountKeys = app.unlocked().keys
        assertTrue(keys.masterKey.any { it != 0.toByte() })
        val accountKey = accountKeyOf(server)

        app.service.signOut()

        assertArrayEquals(ByteArray(MASTER_KEY_BYTES), keys.masterKey)
        assertTrue(keys.privateKey.all { it == 0.toByte() })
        assertEquals(1, server.count(Routes.authSignOut()))
        assertTrue(device.rowStore.destroyCalls.contains(accountKey))
        assertTrue(device.fileStore.destroyCalls.contains(accountKey))
        assertTrue(app.keyVault.forgetCalls.contains(accountKey))
        assertNull(device.sessionStore.load(accountKey))
        assertNull(app.activeAccountKey.value)
        assertTrue(app.accountState.value is AccountState.SignedOut)
        assertFalse(device.databaseFile(accountKey).exists())
        assertFalse(device.accountRoot(accountKey).exists())
    }

    @Test
    fun signOutIgnoresAFailingServer() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = signUpAlice(server, device, sodium)
        server.online = false
        app.service.signOut()
        assertTrue(app.accountState.value is AccountState.SignedOut)
    }

    @Test
    fun keepsTheNoServerStateWhenTheServerCannotBeReached() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium)
        app.accountState.value = AccountState.NoServer
        app.serverUrl.chooseResult = ServerChoice.Failed(ServerChoiceFailure.UNREACHABLE)
        assertEquals(ServerChoice.Failed(ServerChoiceFailure.UNREACHABLE), app.service.chooseServer("x"))
        assertEquals(AccountState.NoServer, app.accountState.value)
        assertEquals(emptyList<Any>(), app.config.accepted.toList())
    }

    @Test
    fun handsTheChosenServerConfigToTheConfigServiceAndLeavesTheNoServerState() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium)
        app.accountState.value = AccountState.NoServer
        val chosen = config(
            sharingEnabled = false,
            publicLinksEnabled = false,
            maxMediaPerEntry = 20,
            maxUploadBytes = 1000,
            defaultQuotaBytes = 0,
            trashDays = 30,
            accountDeletionGraceDays = 7,
            serverVersion = "0.1.0",
        )
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, chosen, null)

        app.service.chooseServer("x")

        assertEquals(listOf(chosen), app.config.accepted.toList())
        assertEquals(AccountState.SignedOut(), app.accountState.value)
        assertEquals(listOf("x"), app.serverUrl.chosen.toList())
    }

    @Test
    fun choosingAServerWhileSignedOutKeepsTheSignedOutState() = runTest {
        val sodium = TestSodium()
        val app = startApp(createFakeAuthServer(), createDevice(), sodium)
        app.accountState.value = AccountState.SignedOut(USERNAME)
        app.serverUrl.chooseResult = ServerChoice.Ok(BASE_URL, config(), null)
        app.service.chooseServer("x")
        assertEquals(AccountState.SignedOut(USERNAME), app.accountState.value)
    }

    @Test
    fun activeUsernameReadsTheRememberedAccount() = runTest {
        val sodium = TestSodium()
        val server = createFakeAuthServer()
        val device = createDevice()
        val app = startApp(server, device, sodium)
        assertNull(app.service.activeUsername())
        app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
        assertEquals(USERNAME, app.service.activeUsername())
        assertEquals(accountKeyOf(server), device.globalStore.activeAccount()?.accountKey)
        app.service.signOut()
        assertNull(app.service.activeUsername())
    }

    private companion object {
        const val NON_DEFAULT_PORT = 8443
    }
}
