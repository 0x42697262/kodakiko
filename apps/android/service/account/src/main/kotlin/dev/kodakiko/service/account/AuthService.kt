package dev.kodakiko.service.account

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.account.HomeCollectionBytes
import dev.kodakiko.account.KdfParams
import dev.kodakiko.account.KeyBundleBytes
import dev.kodakiko.account.OpenedRecovery
import dev.kodakiko.account.PasswordChangeBytes
import dev.kodakiko.account.PasswordChangeInput
import dev.kodakiko.account.RecoveryCompleteBytes
import dev.kodakiko.account.RecoveryCompletionInput
import dev.kodakiko.account.SignUpInput
import dev.kodakiko.account.SignUpMaterial
import dev.kodakiko.account.SignUpRequestBytes
import dev.kodakiko.account.StoredLocalAccount
import dev.kodakiko.account.WrongPasswordError
import dev.kodakiko.account.checkPasswordPolicy
import dev.kodakiko.account.createPasswordChangeMaterial
import dev.kodakiko.account.createRecoveryCompletion
import dev.kodakiko.account.createSignUpMaterial
import dev.kodakiko.account.deriveForSignIn
import dev.kodakiko.account.isValidUsername
import dev.kodakiko.account.normalizeUsername
import dev.kodakiko.account.openKeyBundle
import dev.kodakiko.account.openRecovery
import dev.kodakiko.account.rebuildSignUpRequest
import dev.kodakiko.account.startKdfParams
import dev.kodakiko.contract.HomeCollectionRequest
import dev.kodakiko.contract.KeyBundleDto
import dev.kodakiko.contract.ParamsRequest
import dev.kodakiko.contract.PasswordChangeRequest
import dev.kodakiko.contract.RecoveryCompleteRequest
import dev.kodakiko.contract.RecoveryStartRequest
import dev.kodakiko.contract.SignInRequest
import dev.kodakiko.contract.SignInResponse
import dev.kodakiko.contract.SignUpRequest
import dev.kodakiko.contract.UserDto
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.DecryptionError
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.decodeRecoveryWords
import dev.kodakiko.crypto.encodeRecoveryKey
import dev.kodakiko.crypto.normalizeRecoveryInput
import dev.kodakiko.crypto.unwrapKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.CollectionRow
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.AuthCalls
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.net.HttpConstants
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

interface AuthService {
    val mode: Observable<AccountMode>

    suspend fun restore()

    suspend fun chooseServer(input: String): ServerChoice

    suspend fun signUp(username: String, password: String, inviteToken: String?): List<String>

    suspend fun confirmRecoveryKeySaved()

    suspend fun signIn(username: String, password: String)

    suspend fun unlockWithPassword(password: String)

    suspend fun unlockWithDevice(): Boolean

    suspend fun recoverAccount(username: String, words: String, newPassword: String)

    suspend fun pendingRecoveryWords(): List<String>?

    suspend fun recoveryPending(): Boolean

    suspend fun cancelDeletion()

    suspend fun signOut()

    suspend fun activeUsername(): String?

    suspend fun changePassword(currentPassword: String, newPassword: String)

    suspend fun revealRecoveryWords(password: String): List<String>

    suspend fun verifyPassword(password: String): Boolean

    suspend fun requestAccountDeletion(): String

    fun deletionScheduledFor(): String?

    suspend fun changeServer()

    suspend fun setUpLocal(username: String, password: String): List<String>

    suspend fun connectServer(serverInput: String, inviteToken: String?, username: String, password: String)

    suspend fun eraseLocal()
}

class AuthServiceDeps(
    val sodium: Sodium,
    val http: HttpClient,
    val serverUrl: ServerUrlStore,
    val sessionStore: SessionStorePort,
    val keyVault: KeyVaultPort,
    val rowStore: RowStorePort,
    val fileStore: FileStorePort,
    val globalStore: GlobalStorePort,
    val lifecycle: LifecyclePort,
    val nowIso: () -> String,
    val randomId: () -> String,
    val accountState: Observable<AccountState>,
    val activeAccountKey: Observable<String?>,
    val config: ConfigService,
    val inviteToken: Observable<String?>,
    val deviceLabel: () -> String,
    val homeCollectionName: String,
    val kdfStartParams: (ByteArray) -> KdfParams = ::startKdfParams,
    val showSecuring: () -> Unit,
    val dismissSecuring: () -> Unit,
    val beforeSignOut: suspend () -> Unit = {},
    val ioDispatcher: CoroutineDispatcher,
    val accountMode: Observable<AccountMode> = Observable(AccountMode.SERVER),
)

private class EstablishInput(
    val user: UserDto,
    val token: String,
    val bundle: KeyBundleBytes,
    val keys: AccountKeys,
    val recoveryConfirmed: Boolean,
    val homeCollectionId: String?,
)

private fun wipeKeys(keys: AccountKeys) {
    wipe(keys.masterKey)
    wipe(keys.privateKey)
}

private fun bundleBytes(bundle: KeyBundleDto): KeyBundleBytes =
    KeyBundleBytes(
        salt = decodeBytes(bundle.salt),
        opsLimit = bundle.opsLimit,
        memLimitBytes = bundle.memLimitBytes,
        masterKeyByKek = decodeBytes(bundle.masterKeyByKek),
        recoveryKeyByMaster = decodeBytes(bundle.recoveryKeyByMaster),
        publicKey = decodeBytes(bundle.publicKey),
        privateKeyByMaster = decodeBytes(bundle.privateKeyByMaster),
    )

private fun bundleOf(request: SignUpRequestBytes): KeyBundleBytes =
    KeyBundleBytes(
        salt = request.salt,
        opsLimit = request.opsLimit,
        memLimitBytes = request.memLimitBytes,
        masterKeyByKek = request.masterKeyByKek,
        recoveryKeyByMaster = request.recoveryKeyByMaster,
        publicKey = request.publicKey,
        privateKeyByMaster = request.privateKeyByMaster,
    )

private fun kdfParamsOf(bundle: KeyBundleBytes): KdfParams = KdfParams(bundle.salt, bundle.opsLimit, bundle.memLimitBytes)

private fun withNewWrap(bundle: KeyBundleBytes, salt: ByteArray, opsLimit: Long, memLimitBytes: Long, masterKeyByKek: ByteArray): KeyBundleBytes =
    KeyBundleBytes(salt, opsLimit, memLimitBytes, masterKeyByKek, bundle.recoveryKeyByMaster, bundle.publicKey, bundle.privateKeyByMaster)

private fun requirePolicy(password: String, username: String) {
    val rules = checkPasswordPolicy(password, username)
    if (rules.isNotEmpty()) throw AuthError(AuthFailure.PASSWORD_POLICY, rules = rules)
}

private fun signUpRequestDto(request: SignUpRequestBytes, inviteToken: String?, deviceLabel: String): SignUpRequest =
    SignUpRequest(
        userId = request.userId,
        username = request.username,
        inviteToken = inviteToken,
        deviceLabel = deviceLabel,
        salt = encodeBytes(request.salt),
        opsLimit = request.opsLimit,
        memLimitBytes = request.memLimitBytes,
        loginKey = encodeBytes(request.loginKey),
        recoveryLoginKey = encodeBytes(request.recoveryLoginKey),
        publicKey = encodeBytes(request.publicKey),
        masterKeyByKek = encodeBytes(request.masterKeyByKek),
        masterKeyByRecovery = encodeBytes(request.masterKeyByRecovery),
        recoveryKeyByMaster = encodeBytes(request.recoveryKeyByMaster),
        privateKeyByMaster = encodeBytes(request.privateKeyByMaster),
        homeCollection = HomeCollectionRequest(
            id = request.homeCollection.id,
            keyByOwner = encodeBytes(request.homeCollection.keyByOwner),
            metadataBlob = encodeBytes(request.homeCollection.metadataBlob),
        ),
    )

private fun failureOf(reason: ServerChoiceFailure): AuthFailure =
    when (reason) {
        ServerChoiceFailure.NOT_HTTPS -> AuthFailure.NOT_HTTPS
        ServerChoiceFailure.UNREACHABLE -> AuthFailure.UNREACHABLE
        ServerChoiceFailure.INVALID, ServerChoiceFailure.NOT_KODAKIKO -> AuthFailure.NOT_KODAKIKO
    }

class DefaultAuthService(private val deps: AuthServiceDeps) : AuthService {
    private val sodium = deps.sodium
    private val http = deps.http
    private val rowStore = deps.rowStore
    private val fileStore = deps.fileStore
    private val sessionStore = deps.sessionStore
    private val keyVault = deps.keyVault
    private val globalStore = deps.globalStore
    private val accountState = deps.accountState

    override val mode: Observable<AccountMode> = deps.accountMode

    @Volatile
    private var previousState: AccountState = accountState.value

    @Volatile
    private var deletionNotice: String? = null

    init {
        accountState.subscribe { next ->
            val previous = previousState
            if (previous is AccountState.Unlocked) {
                val keysChanged = next !is AccountState.Unlocked || next.keys !== previous.keys
                if (keysChanged) wipeKeys(previous.keys)
            }
            previousState = next
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(deps.ioDispatcher) { block() }

    private suspend fun attempt(step: suspend () -> Unit) {
        try {
            step()
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
        }
    }

    private fun readActiveAccount(): ActiveAccount? =
        globalStore.activeAccount()?.takeIf { it.schemaVersion <= ActiveAccount.CURRENT_SCHEMA_VERSION }

    private fun writeActiveAccount(accountKey: String, username: String, accountMode: AccountMode) {
        mode.value = accountMode
        globalStore.setActiveAccount(ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, accountKey, username, accountMode))
    }

    private fun setSignedOut(username: String?) {
        accountState.value = AccountState.SignedOut(username)
    }

    private suspend fun accountKeyFor(userId: String): String {
        val baseUrl = deps.serverUrl.current() ?: throw AuthError(AuthFailure.GENERIC)
        val host = ServerUrls.host(baseUrl) ?: throw AuthError(AuthFailure.GENERIC)
        return host + AccountConstants.ACCOUNT_KEY_SEPARATOR + userId
    }

    private fun openStores(accountKey: String) {
        rowStore.open(accountKey)
        fileStore.open(accountKey)
    }

    private fun requireAccountKey(): String = deps.activeAccountKey.value ?: throw AuthError(AuthFailure.GENERIC)

    private fun requireStored(): StoredAccount = AccountStorage.loadAccount(rowStore) ?: throw AuthError(AuthFailure.GENERIC)

    private fun unlockedState(userId: String, username: String, keys: AccountKeys) {
        accountState.value = AccountState.Unlocked(keys = keys, userId = userId, username = username)
    }

    private fun unwrapPrivateKey(masterKey: ByteArray, userId: String, wrapped: ByteArray): ByteArray =
        unwrapKey(sodium, masterKey, wrapped, AdContext.PRIVATE_KEY_BY_MASTER, userId)

    private suspend fun persistAccount(input: EstablishInput) {
        val accountKey = accountKeyFor(input.user.id)
        sessionStore.save(accountKey, input.token)
        deps.activeAccountKey.value = accountKey
        writeActiveAccount(accountKey, input.user.username, AccountMode.SERVER)
        openStores(accountKey)
        AccountStorage.saveAccountData(
            rowStore,
            AccountData(
                userId = input.user.id,
                username = input.user.username,
                bundle = input.bundle,
                recoveryConfirmed = input.recoveryConfirmed,
                homeCollectionId = input.homeCollectionId,
                deletionDueAt = input.user.deletionDueAt,
                role = input.user.role.wire,
            ),
        )
        keyVault.storeMasterKey(accountKey, input.keys.masterKey)
    }

    private suspend fun establish(input: EstablishInput) {
        try {
            persistAccount(input)
        } catch (error: Throwable) {
            wipeKeys(input.keys)
            throw error
        }
        if (input.user.status == UserStatus.PENDING_DELETION) {
            wipeKeys(input.keys)
            accountState.value = AccountState.PendingDeletion
        } else {
            unlockedState(input.user.id, input.user.username, input.keys)
        }
    }

    private suspend fun establishFromSignIn(response: SignInResponse, keys: AccountKeys) {
        establish(
            EstablishInput(
                user = response.user,
                token = response.session.token,
                bundle = bundleBytes(response.keyBundle),
                keys = keys,
                recoveryConfirmed = true,
                homeCollectionId = null,
            ),
        )
    }

    override suspend fun unlockWithDevice(): Boolean = io { unlockWithDeviceOnIo() }

    private fun unlockWithDeviceOnIo(): Boolean {
        val accountKey = deps.activeAccountKey.value ?: return false
        val stored = AccountStorage.loadAccount(rowStore) ?: return false
        val masterKey = keyVault.loadMasterKey(accountKey) ?: return false
        val privateKey = try {
            unwrapPrivateKey(masterKey, stored.userId, stored.bundle.privateKeyByMaster)
        } catch (error: Throwable) {
            wipe(masterKey)
            if (error is DecryptionError) return false
            throw error
        }
        unlockedState(stored.userId, stored.username, AccountKeys(masterKey, privateKey, stored.bundle.publicKey))
        return true
    }

    private fun openWithPassword(password: String, userId: String, bundle: KeyBundleBytes): AccountKeys {
        val login = deriveForSignIn(sodium, password, kdfParamsOf(bundle))
        wipe(login.loginKey)
        try {
            return openKeyBundle(sodium, login.kek, userId, bundle)
        } finally {
            wipe(login.kek)
        }
    }

    private fun fetchFreshBundle(accountKey: String, username: String): KeyBundleBytes? =
        try {
            bundleBytes(http.request(AuthCalls.keyBundle()))
        } catch (error: Exception) {
            if (error is ApiError && error.status == HttpConstants.STATUS_UNAUTHORIZED) {
                sessionStore.clear(accountKey)
                setSignedOut(username)
                throw AuthError(AuthFailure.INVALID_CREDENTIALS)
            }
            null
        }

    override suspend fun unlockWithPassword(password: String) = io { unlockWithPasswordOnIo(password) }

    private fun unlockWithPasswordOnIo(password: String) {
        val stored = AccountStorage.loadAccount(rowStore) ?: throw AuthError(AuthFailure.GENERIC)
        val accountKey = requireAccountKey()
        val keys = try {
            try {
                openWithPassword(password, stored.userId, stored.bundle)
            } catch (error: Throwable) {
                if (error !is WrongPasswordError || mode.value == AccountMode.LOCAL) throw error
                val fresh = fetchFreshBundle(accountKey, stored.username) ?: throw error
                val opened = openWithPassword(password, stored.userId, fresh)
                try {
                    AccountStorage.saveKeyBundle(rowStore, fresh)
                } catch (failure: Throwable) {
                    wipeKeys(opened)
                    throw failure
                }
                opened
            }
        } catch (error: Throwable) {
            throw toAuthError(error)
        }
        try {
            keyVault.storeMasterKey(accountKey, keys.masterKey)
        } catch (error: Throwable) {
            wipeKeys(keys)
            throw error
        }
        unlockedState(stored.userId, stored.username, keys)
    }

    private fun currentSessionUser(): UserDto? =
        try {
            http.request(AuthCalls.session()).user
        } catch (error: ApiError) {
            if (error.status == HttpConstants.STATUS_UNAUTHORIZED) throw error
            null
        } catch (error: NetworkError) {
            null
        }

    override suspend fun restore() = io { restoreOnIo() }

    private suspend fun restoreOnIo() {
        val remembered = readActiveAccount()
        if (remembered?.mode == AccountMode.LOCAL) {
            deps.activeAccountKey.value = remembered.accountKey
            mode.value = AccountMode.LOCAL
            openStores(remembered.accountKey)
            accountState.value = AccountState.Locked
            unlockWithDeviceOnIo()
            return
        }
        if (deps.serverUrl.current() == null) {
            accountState.value = AccountState.NoServer
            return
        }
        val active = readActiveAccount()
        if (active == null) {
            setSignedOut(null)
            return
        }
        deps.activeAccountKey.value = active.accountKey
        if (sessionStore.load(active.accountKey) == null) {
            setSignedOut(active.username)
            return
        }
        val user = try {
            currentSessionUser()
        } catch (error: ApiError) {
            sessionStore.clear(active.accountKey)
            setSignedOut(active.username)
            return
        }
        openStores(active.accountKey)
        if (user?.status == UserStatus.PENDING_DELETION) {
            AccountStorage.saveDeletionDueAt(rowStore, user.deletionDueAt)
            accountState.value = AccountState.PendingDeletion
            return
        }
        if (user != null) AccountStorage.saveDeletionDueAt(rowStore, null)
        accountState.value = AccountState.Locked
        unlockWithDeviceOnIo()
    }

    override suspend fun chooseServer(input: String): ServerChoice {
        val choice = deps.serverUrl.choose(input)
        if (choice !is ServerChoice.Ok) return choice
        io { deps.config.accept(choice.config) }
        if (accountState.value is AccountState.NoServer) setSignedOut(null)
        return choice
    }

    override suspend fun signUp(username: String, password: String, inviteToken: String?): List<String> {
        val normalized = normalizeUsername(username)
        if (!isValidUsername(normalized)) throw AuthError(AuthFailure.USERNAME_INVALID)
        requirePolicy(password, normalized)
        deps.showSecuring()
        deps.lifecycle.afterInteractions()
        try {
            return io { signUpOnIo(normalized, password, inviteToken) }
        } catch (error: Throwable) {
            deps.dismissSecuring()
            throw toAuthError(error)
        }
    }

    private fun newSignUpMaterial(username: String, password: String): SignUpMaterial =
        createSignUpMaterial(
            sodium,
            SignUpInput(
                username = username,
                password = password,
                homeCollectionName = deps.homeCollectionName,
                createdAtUtc = deps.nowIso(),
                randomId = deps.randomId,
            ),
            deps.kdfStartParams,
        )

    private fun wipeMaterial(material: SignUpMaterial) {
        wipe(material.request.loginKey)
        wipe(material.request.recoveryLoginKey)
        wipe(material.secrets.homeCollectionKey)
    }

    private suspend fun signUpOnIo(username: String, password: String, inviteToken: String?): List<String> {
        var material: SignUpMaterial? = null
        try {
            val created = newSignUpMaterial(username, password)
            material = created
            val request = created.request
            val secrets = created.secrets
            val response = http.request(AuthCalls.signUp(signUpRequestDto(request, inviteToken, deps.deviceLabel())))
            establish(
                EstablishInput(
                    user = response.user,
                    token = response.session.token,
                    bundle = bundleOf(request),
                    keys = AccountKeys(secrets.masterKey, secrets.privateKey, secrets.publicKey),
                    recoveryConfirmed = false,
                    homeCollectionId = secrets.homeCollectionId,
                ),
            )
            deps.inviteToken.value = null
            return created.recoveryWords
        } catch (error: Throwable) {
            material?.let {
                wipe(it.secrets.masterKey)
                wipe(it.secrets.privateKey)
            }
            throw error
        } finally {
            material?.let(::wipeMaterial)
        }
    }

    override suspend fun confirmRecoveryKeySaved() {
        io { rowStore.kvSetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED, JsonPrimitive(true)) }
        val state = accountState.value
        if (state is AccountState.Unlocked) accountState.value = state
    }

    override suspend fun signIn(username: String, password: String) {
        val normalized = normalizeUsername(username)
        try {
            io { signInOnIo(normalized, password) }
        } catch (error: Throwable) {
            throw toAuthError(error)
        }
    }

    private suspend fun signInOnIo(username: String, password: String) {
        val params = http.request(AuthCalls.params(ParamsRequest(username)))
        val login = deriveForSignIn(sodium, password, KdfParams(decodeBytes(params.salt), params.opsLimit, params.memLimitBytes))
        try {
            val response = http.request(AuthCalls.signIn(SignInRequest(username, encodeBytes(login.loginKey), deps.deviceLabel())))
            val keys = openKeyBundle(sodium, login.kek, response.user.id, bundleBytes(response.keyBundle))
            establishFromSignIn(response, keys)
        } finally {
            wipe(login.kek)
            wipe(login.loginKey)
        }
    }

    override suspend fun recoverAccount(username: String, words: String, newPassword: String) {
        if (mode.value == AccountMode.LOCAL) {
            io { recoverLocalOnIo(words, newPassword) }
            return
        }
        val normalized = normalizeUsername(username)
        io { recoverOnIo(normalized, words, newPassword) }
    }

    private suspend fun recoverOnIo(username: String, wordsInput: String, newPassword: String) {
        var opened: OpenedRecovery? = null
        var completion: RecoveryCompleteBytes? = null
        try {
            val words = normalizeRecoveryInput(wordsInput)
            wipe(decodeRecoveryWords(sodium, words))
            val start = http.request(AuthCalls.recoveryStart(RecoveryStartRequest(username)))
            val recovery = openRecovery(sodium, words, start.userId, decodeBytes(start.masterKeyByRecovery))
            opened = recovery
            requirePolicy(newPassword, username)
            val done = createRecoveryCompletion(
                sodium,
                RecoveryCompletionInput(start.userId, newPassword, recovery.masterKey, recovery.recoveryKey),
                deps.kdfStartParams,
            )
            completion = done
            val response = http.request(
                AuthCalls.recoveryComplete(
                    RecoveryCompleteRequest(
                        username = username,
                        recoveryLoginKey = encodeBytes(done.recoveryLoginKey),
                        deviceLabel = deps.deviceLabel(),
                        salt = encodeBytes(done.salt),
                        opsLimit = done.opsLimit,
                        memLimitBytes = done.memLimitBytes,
                        loginKey = encodeBytes(done.loginKey),
                        masterKeyByKek = encodeBytes(done.masterKeyByKek),
                    ),
                ),
            )
            val bundle = bundleBytes(response.keyBundle)
            val privateKey = unwrapPrivateKey(recovery.masterKey, response.user.id, bundle.privateKeyByMaster)
            establish(
                EstablishInput(
                    user = response.user,
                    token = response.session.token,
                    bundle = bundle,
                    keys = AccountKeys(recovery.masterKey, privateKey, bundle.publicKey),
                    recoveryConfirmed = true,
                    homeCollectionId = null,
                ),
            )
        } catch (error: Throwable) {
            opened?.let { wipe(it.masterKey) }
            throw toAuthError(error)
        } finally {
            opened?.let { wipe(it.recoveryKey) }
            completion?.let {
                wipe(it.loginKey)
                wipe(it.recoveryLoginKey)
            }
        }
    }

    override suspend fun pendingRecoveryWords(): List<String>? {
        val state = accountState.value as? AccountState.Unlocked ?: return null
        return io {
            val stored = AccountStorage.loadAccount(rowStore)
            if (stored == null || stored.recoveryConfirmed) {
                null
            } else {
                recoveryWordsOf(state.keys.masterKey, stored)
            }
        }
    }

    private fun recoveryWordsOf(masterKey: ByteArray, stored: StoredAccount): List<String> {
        val recoveryKey = unwrapKey(sodium, masterKey, stored.bundle.recoveryKeyByMaster, AdContext.RECOVERY_KEY_BY_MASTER, stored.userId)
        try {
            return encodeRecoveryKey(sodium, recoveryKey)
        } finally {
            wipe(recoveryKey)
        }
    }

    override suspend fun recoveryPending(): Boolean =
        try {
            io { AccountStorage.readBoolean(rowStore, StorageKeys.ACCOUNT_RECOVERY_CONFIRMED) } == false
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            true
        }

    override suspend fun cancelDeletion() {
        try {
            io { http.request(AuthCalls.cancelDeletion()) }
        } catch (error: Throwable) {
            throw toAuthError(error)
        }
        restore()
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) = io { changePasswordOnIo(currentPassword, newPassword) }

    private fun changePasswordOnIo(currentPassword: String, newPassword: String) {
        val stored = requireStored()
        requirePolicy(newPassword, stored.username)
        val accountKey = requireAccountKey()
        val material: PasswordChangeBytes = try {
            createPasswordChangeMaterial(
                sodium,
                PasswordChangeInput(
                    userId = stored.userId,
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                    currentParams = kdfParamsOf(stored.bundle),
                    cachedMasterKeyByKek = stored.bundle.masterKeyByKek,
                ),
                deps.kdfStartParams,
            )
        } catch (error: Throwable) {
            throw toAuthError(error)
        }
        val newBundle = withNewWrap(stored.bundle, material.salt, material.opsLimit, material.memLimitBytes, material.masterKeyByKek)
        try {
            if (mode.value == AccountMode.LOCAL) {
                AccountStorage.saveKeyBundle(rowStore, newBundle)
                return
            }
            try {
                val response = http.request(
                    AuthCalls.changePassword(
                        PasswordChangeRequest(
                            currentLoginKey = encodeBytes(material.currentLoginKey),
                            salt = encodeBytes(material.salt),
                            opsLimit = material.opsLimit,
                            memLimitBytes = material.memLimitBytes,
                            loginKey = encodeBytes(material.loginKey),
                            masterKeyByKek = encodeBytes(material.masterKeyByKek),
                        ),
                    ),
                )
                sessionStore.save(accountKey, response.session.token)
                AccountStorage.saveKeyBundle(rowStore, newBundle)
            } catch (error: Throwable) {
                throw toAuthError(error)
            }
        } finally {
            wipe(material.currentLoginKey)
            wipe(material.loginKey)
        }
    }

    override suspend fun revealRecoveryWords(password: String): List<String> =
        io {
            val stored = requireStored()
            val keys = try {
                openWithPassword(password, stored.userId, stored.bundle)
            } catch (error: Throwable) {
                throw toAuthError(error)
            }
            try {
                recoveryWordsOf(keys.masterKey, stored)
            } finally {
                wipeKeys(keys)
            }
        }

    override suspend fun verifyPassword(password: String): Boolean =
        io {
            val stored = requireStored()
            try {
                wipeKeys(openWithPassword(password, stored.userId, stored.bundle))
                true
            } catch (error: Throwable) {
                if (error !is WrongPasswordError && error !is DecryptionError) throw error
                false
            }
        }

    override suspend fun requestAccountDeletion(): String {
        val (stored, accountKey) = io { requireStored() to requireAccountKey() }
        val response = try {
            io { http.request(AuthCalls.requestDeletion()) }
        } catch (error: Throwable) {
            throw toAuthError(error)
        }
        deletionNotice = response.deletionDueAt
        io { AccountStorage.saveDeletionDueAt(rowStore, response.deletionDueAt) }
        attempt { io { sessionStore.clear(accountKey) } }
        setSignedOut(stored.username)
        return response.deletionDueAt
    }

    override fun deletionScheduledFor(): String? = deletionNotice

    override suspend fun changeServer() {
        signOut()
        attempt { deps.serverUrl.clear() }
        attempt { io { globalStore.clearServerConfigJson() } }
        deletionNotice = null
        accountState.value = AccountState.NoServer
    }

    override suspend fun setUpLocal(username: String, password: String): List<String> {
        val normalized = normalizeUsername(username)
        if (!isValidUsername(normalized)) throw AuthError(AuthFailure.USERNAME_INVALID)
        requirePolicy(password, normalized)
        deps.showSecuring()
        deps.lifecycle.afterInteractions()
        try {
            return io { setUpLocalOnIo(normalized, password) }
        } catch (error: Throwable) {
            deps.dismissSecuring()
            throw toAuthError(error)
        }
    }

    private fun setUpLocalOnIo(username: String, password: String): List<String> {
        var material: SignUpMaterial? = null
        try {
            val created = newSignUpMaterial(username, password)
            material = created
            val request = created.request
            val secrets = created.secrets
            val accountKey = AccountConstants.LOCAL_ACCOUNT_KEY_PREFIX + AccountConstants.ACCOUNT_KEY_SEPARATOR + request.userId
            deps.activeAccountKey.value = accountKey
            openStores(accountKey)
            AccountStorage.saveAccountData(
                rowStore,
                AccountData(
                    userId = request.userId,
                    username = username,
                    bundle = bundleOf(request),
                    recoveryConfirmed = false,
                    homeCollectionId = secrets.homeCollectionId,
                    deletionDueAt = null,
                ),
            )
            rowStore.kvSet(StorageKeys.ACCOUNT_MASTER_KEY_BY_RECOVERY, request.masterKeyByRecovery)
            rowStore.kvSetJson(StorageKeys.ACCOUNT_MODE, JsonPrimitive(AccountMode.LOCAL.wire))
            rowStore.transaction { tx ->
                tx.collections.put(
                    CollectionRow(
                        id = request.homeCollection.id,
                        kind = CollectionKind.HOME,
                        ownerId = request.userId,
                        ownerUsername = username,
                        role = CollectionRole.OWNER,
                        keyMaterial = request.homeCollection.keyByOwner,
                        metadataBlob = request.homeCollection.metadataBlob,
                        metaUpdatedAt = deps.nowIso(),
                        serverLastSeq = 0,
                        cursorSeq = 0,
                    ),
                )
            }
            keyVault.storeMasterKey(accountKey, secrets.masterKey)
            writeActiveAccount(accountKey, username, AccountMode.LOCAL)
            unlockedState(request.userId, username, AccountKeys(secrets.masterKey, secrets.privateKey, secrets.publicKey))
            return created.recoveryWords
        } catch (error: Throwable) {
            material?.let {
                wipe(it.secrets.masterKey)
                wipe(it.secrets.privateKey)
            }
            throw error
        } finally {
            material?.let(::wipeMaterial)
        }
    }

    private fun storedLocalAccount(): StoredLocalAccount {
        val stored = requireStored()
        val masterKeyByRecovery = rowStore.kvGet(StorageKeys.ACCOUNT_MASTER_KEY_BY_RECOVERY)
        val homeId = AccountStorage.readString(rowStore, StorageKeys.ACCOUNT_HOME_COLLECTION_ID)
        val home = homeId?.let { id -> rowStore.transaction { it.collections.get(id) } }
        if (masterKeyByRecovery == null || home == null) throw AuthError(AuthFailure.GENERIC)
        return StoredLocalAccount(
            userId = stored.userId,
            kdfParams = kdfParamsOf(stored.bundle),
            masterKeyByKek = stored.bundle.masterKeyByKek,
            masterKeyByRecovery = masterKeyByRecovery,
            recoveryKeyByMaster = stored.bundle.recoveryKeyByMaster,
            publicKey = stored.bundle.publicKey,
            privateKeyByMaster = stored.bundle.privateKeyByMaster,
            homeCollection = HomeCollectionBytes(home.id, home.keyMaterial, home.metadataBlob),
        )
    }

    private fun recoverLocalOnIo(wordsInput: String, newPassword: String) {
        val local = storedLocalAccount()
        val stored = requireStored()
        var opened: OpenedRecovery? = null
        try {
            val words = normalizeRecoveryInput(wordsInput)
            val recovery = openRecovery(sodium, words, local.userId, local.masterKeyByRecovery)
            opened = recovery
            requirePolicy(newPassword, stored.username)
            val completion = createRecoveryCompletion(
                sodium,
                RecoveryCompletionInput(local.userId, newPassword, recovery.masterKey, recovery.recoveryKey),
                deps.kdfStartParams,
            )
            wipe(completion.loginKey)
            wipe(completion.recoveryLoginKey)
            AccountStorage.saveKeyBundle(
                rowStore,
                withNewWrap(stored.bundle, completion.salt, completion.opsLimit, completion.memLimitBytes, completion.masterKeyByKek),
            )
            rowStore.kvSetJson(StorageKeys.ACCOUNT_RECOVERY_CONFIRMED, JsonPrimitive(true))
            val accountKey = requireAccountKey()
            keyVault.storeMasterKey(accountKey, recovery.masterKey)
            val privateKey = unwrapPrivateKey(recovery.masterKey, local.userId, stored.bundle.privateKeyByMaster)
            unlockedState(stored.userId, stored.username, AccountKeys(recovery.masterKey, privateKey, stored.bundle.publicKey))
        } catch (error: Throwable) {
            opened?.let { wipe(it.masterKey) }
            throw toAuthError(error)
        } finally {
            opened?.let { wipe(it.recoveryKey) }
        }
    }

    override suspend fun connectServer(serverInput: String, inviteToken: String?, username: String, password: String) {
        val normalized = normalizeUsername(username)
        if (!isValidUsername(normalized)) throw AuthError(AuthFailure.USERNAME_INVALID)
        val accountKey = requireAccountKey()
        val local = io { storedLocalAccount() }
        val choice = when (val chosen = deps.serverUrl.choose(serverInput)) {
            is ServerChoice.Failed -> throw AuthError(failureOf(chosen.reason))
            is ServerChoice.Ok -> chosen
        }
        try {
            io { connectOnIo(choice, local, accountKey, inviteToken, normalized, password) }
        } catch (error: Throwable) {
            attempt { deps.serverUrl.clear() }
            throw toAuthError(error)
        }
    }

    private fun connectOnIo(choice: ServerChoice.Ok, local: StoredLocalAccount, accountKey: String, inviteToken: String?, username: String, password: String) {
        val request = rebuildSignUpRequest(sodium, password, username, local)
        try {
            val response = http.request(AuthCalls.signUp(signUpRequestDto(request, inviteToken ?: choice.inviteToken, deps.deviceLabel())))
            deps.config.accept(choice.config)
            sessionStore.save(accountKey, response.session.token)
            rowStore.kvSetJson(StorageKeys.ACCOUNT_MODE, JsonPrimitive(AccountMode.SERVER.wire))
            rowStore.kvSetJson(StorageKeys.ACCOUNT_USERNAME, JsonPrimitive(username))
            AccountStorage.saveRole(rowStore, response.user.role.wire)
            writeActiveAccount(accountKey, username, AccountMode.SERVER)
            val state = accountState.value
            if (state is AccountState.Unlocked) accountState.value = state.copy(username = username)
        } finally {
            wipe(request.loginKey)
            wipe(request.recoveryLoginKey)
        }
    }

    override suspend fun eraseLocal() {
        attempt { deps.beforeSignOut() }
        withContext(NonCancellable + deps.ioDispatcher) {
            val accountKey = deps.activeAccountKey.value
            if (accountKey != null) {
                attempt { keyVault.forget(accountKey) }
                attempt { rowStore.destroy(accountKey) }
                attempt { fileStore.destroy(accountKey) }
            }
            deps.activeAccountKey.value = null
            attempt { globalStore.clearActiveAccount() }
            mode.value = AccountMode.SERVER
            accountState.value = if (deps.serverUrl.current() == null) AccountState.NoServer else AccountState.SignedOut()
        }
    }

    override suspend fun signOut() {
        attempt { deps.beforeSignOut() }
        withContext(NonCancellable + deps.ioDispatcher) {
            val accountKey = deps.activeAccountKey.value
            attempt { http.request(AuthCalls.signOut()) }
            if (accountKey != null) {
                attempt { sessionStore.clear(accountKey) }
                attempt { keyVault.forget(accountKey) }
                attempt { rowStore.destroy(accountKey) }
                attempt { fileStore.destroy(accountKey) }
            }
            deps.activeAccountKey.value = null
            attempt { globalStore.clearActiveAccount() }
            deps.inviteToken.value = null
            setSignedOut(null)
        }
    }

    override suspend fun activeUsername(): String? = io { readActiveAccount()?.username }
}
