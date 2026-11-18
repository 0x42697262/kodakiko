@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.account

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.account.ARGON2ID_MIN_MEM_BYTES
import dev.kodakiko.account.KdfParams
import dev.kodakiko.account.KeyBundleBytes
import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.DeletionRequestResponse
import dev.kodakiko.contract.ErrorBody
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.ErrorResponse
import dev.kodakiko.contract.KeyBundleDto
import dev.kodakiko.contract.ParamsResponse
import dev.kodakiko.contract.PasswordChangeResponse
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.RecoveryStartResponse
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SessionDto
import dev.kodakiko.contract.SessionResponse
import dev.kodakiko.contract.SignInResponse
import dev.kodakiko.contract.SignUpResponse
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.contract.UserDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.Argon2idInput
import dev.kodakiko.crypto.BoxKeypair
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.concatBytes
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.utf8Encode
import dev.kodakiko.crypto.wipe
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.files.AccountPaths
import dev.kodakiko.data.files.accountHash
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.secure.ActiveAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.net.InetAddress
import java.util.Collections

const val BASE_URL = "https://journal.example.test"
const val HOST = "journal.example.test"
const val USERNAME = "alice"
const val PASSWORD = "correct horse battery staple"
const val NEW_PASSWORD = "another long passphrase here"
const val FAKE_ARGON2_OPS = 2L
const val FAKE_ARGON2_MEM_BYTES = 8_388_608L
const val FLOOR_OPS = 32L
const val KEY_LENGTH = 32
const val MASTER_KEY_BYTES = 32
const val WORDS_PER_KEY = 24
const val ID_BYTES = 16
const val SALT_LENGTH = 16
const val EPOCH_ISO = "1970-01-01T00:00:00.000Z"
const val ONE_DAY_ISO = "1970-01-02T00:00:00.000Z"
const val DEVICE_LABEL = "Test Device"
const val HOME_COLLECTION_NAME = "Journal"
const val SESSION_TOKEN_PATTERN = "^kdk_s_.*"
val VALID_INVITE = "kdk_i_" + "a".repeat(43)
val UPPER_INVITE = "kdk_i_" + "A".repeat(43)

val sharedOkHttp: OkHttpClient by lazy { OkHttpClient() }

fun floorParams(salt: ByteArray): KdfParams = KdfParams(salt, FLOOR_OPS, ARGON2ID_MIN_MEM_BYTES)

fun randomIdOf(sodium: Sodium): () -> String = { encodeBytes(sodium.randomBytes(ID_BYTES)) }

fun bundleBytes(bundle: KeyBundleDto): KeyBundleBytes =
    KeyBundleBytes(
        salt = decodeBytes(bundle.salt),
        opsLimit = bundle.opsLimit,
        memLimitBytes = bundle.memLimitBytes,
        masterKeyByKek = decodeBytes(bundle.masterKeyByKek),
        recoveryKeyByMaster = decodeBytes(bundle.recoveryKeyByMaster),
        publicKey = decodeBytes(bundle.publicKey),
        privateKeyByMaster = decodeBytes(bundle.privateKeyByMaster),
    )

fun config(
    signupMode: SignupMode = SignupMode.INVITE,
    sharingEnabled: Boolean = false,
    publicLinksEnabled: Boolean = false,
    maxMediaPerEntry: Long = 20,
    maxUploadBytes: Long = 1000,
    defaultQuotaBytes: Long = 0,
    trashDays: Long = 30,
    accountDeletionGraceDays: Long = 7,
    serverVersion: String = "0.1.0",
): PublicConfigDto =
    PublicConfigDto(
        signupMode = signupMode,
        sharingEnabled = sharingEnabled,
        publicLinksEnabled = publicLinksEnabled,
        maxMediaPerEntry = maxMediaPerEntry,
        maxUploadBytes = maxUploadBytes,
        defaultQuotaBytes = defaultQuotaBytes,
        trashDays = trashDays,
        accountDeletionGraceDays = accountDeletionGraceDays,
        serverVersion = serverVersion,
    )

fun configJson(value: PublicConfigDto): String = ContractJson.stringify(ContractSchemas.publicConfigSchema.encode(value))

fun disconnectResponse(): MockResponse = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

fun jsonResponse(status: Int, element: JsonElement): MockResponse =
    MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(ContractJson.stringify(element)).build()

fun errorResponse(status: Int, code: ErrorCode, retryAfterSeconds: Int? = null): MockResponse {
    val body = ContractSchemas.errorResponseSchema.encode(ErrorResponse(ErrorBody(code, code.wire, "req-fake", JsonNull)))
    val builder = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(ContractJson.stringify(body))
    if (retryAfterSeconds != null) builder.addHeader("Retry-After", retryAfterSeconds.toString())
    return builder.build()
}

suspend fun authFailure(block: suspend () -> Unit): AuthError {
    try {
        block()
    } catch (error: AuthError) {
        return error
    }
    throw AssertionError("expected an AuthError")
}

suspend fun anyFailure(block: suspend () -> Unit): Throwable {
    try {
        block()
    } catch (error: Throwable) {
        return error
    }
    throw AssertionError("expected a failure")
}

class TestSodium(private val base: Sodium = createSodium()) : Sodium by base {
    @Volatile
    var argon2Calls = 0
        private set

    val secrets: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    private fun capture(buffer: ByteArray): ByteArray {
        if (buffer.size == KEY_LENGTH) secrets.add(buffer)
        return buffer
    }

    fun clearSecrets() = secrets.clear()

    override fun argon2id(input: Argon2idInput): ByteArray {
        argon2Calls += 1
        val salted = concatBytes(input.password, utf8Encode("${input.opsLimit}:${input.memLimitBytes}"))
        return capture(base.argon2id(Argon2idInput(salted, input.salt, FAKE_ARGON2_OPS, FAKE_ARGON2_MEM_BYTES, input.outputLength)))
    }

    override fun kdfDerive(subkeyLength: Int, subkeyId: Long, context: String, key: ByteArray): ByteArray =
        capture(base.kdfDerive(subkeyLength, subkeyId, context, key))

    override fun aeadEncrypt(plaintext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        capture(key)
        capture(plaintext)
        return base.aeadEncrypt(plaintext, additionalData, nonce, key)
    }

    override fun aeadDecrypt(ciphertext: ByteArray, additionalData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray =
        capture(base.aeadDecrypt(ciphertext, additionalData, nonce, key))

    override fun boxKeypair(): BoxKeypair {
        val pair = base.boxKeypair()
        capture(pair.privateKey)
        return pair
    }
}

fun TestSodium.assertWiped(except: List<ByteArray> = emptyList()) {
    val snapshot = synchronized(secrets) { secrets.toList() }
    val leaked = snapshot.filter { buffer -> except.none { it === buffer } && buffer.any { it != 0.toByte() } }
    assertEquals(0, leaked.size)
    assertTrue(snapshot.isNotEmpty())
}

class StoredUser(
    val id: String,
    val username: String,
    var role: UserRole,
    var status: UserStatus,
    var deletionDueAt: String?,
    var loginKey: String,
    val recoveryLoginKey: String,
    var bundle: KeyBundleDto,
    val masterKeyByRecovery: String,
)

data class RecordedCall(val method: String, val path: String)

private class PlannedFailure(val status: Int, val code: ErrorCode, var remaining: Int, val retryAfterSeconds: Int?)

private class FakeServerError(val status: Int, val code: ErrorCode) : Exception(code.wire)

class FakeAuthServer : Dispatcher() {
    private val mock = MockWebServer()
    private val users = LinkedHashMap<String, StoredUser>()
    private val sessions = LinkedHashMap<String, String>()
    private val planned = HashMap<String, PlannedFailure>()
    private var tokenCounter = 0

    val calls: MutableList<RecordedCall> = Collections.synchronizedList(mutableListOf())

    val signUpInvites: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var online = true

    @Volatile
    var hostileParams = false

    @Volatile
    var stallSignOut = false

    fun start(): FakeAuthServer {
        mock.dispatcher = this
        mock.start(InetAddress.getByName(LOOPBACK), 0)
        return this
    }

    fun shutdown() = mock.close()

    val baseUrl: String get() = mock.url("/").toString().trimEnd('/')

    fun user(username: String): StoredUser = synchronized(this) { users[username] ?: error("unknown user $username") }

    fun replaceBundle(username: String, loginKey: ByteArray?, bundle: KeyBundleDto) = synchronized(this) {
        val stored = user(username)
        if (loginKey != null) stored.loginKey = encodeBytes(loginKey)
        stored.bundle = bundle
    }

    fun revokeSessions() = synchronized(this) { sessions.clear() }

    fun requestDeletion(username: String) = synchronized(this) {
        markPendingDeletion(username)
        sessions.clear()
    }

    fun markPendingDeletion(username: String) = synchronized(this) {
        val stored = user(username)
        stored.status = UserStatus.PENDING_DELETION
        stored.deletionDueAt = ONE_DAY_ISO
    }

    fun failOnce(path: String, status: Int, code: ErrorCode) = fail(path, status, code, 1, null)

    fun fail(path: String, status: Int, code: ErrorCode, times: Int, retryAfterSeconds: Int?) = synchronized(this) {
        planned[path] = PlannedFailure(status, code, times, retryAfterSeconds)
    }

    fun count(path: String): Int = synchronized(calls) { calls.count { it.path == path } }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val method = request.method
        val path = request.url.encodedPath
        calls.add(RecordedCall(method, path))
        if (!online) return disconnectResponse()
        if (stallSignOut && path == Routes.authSignOut()) return MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()
        return synchronized(this) { respond(method, path, request) }
    }

    private fun respond(method: String, path: String, request: RecordedRequest): MockResponse {
        val handler = handlers["$method $path"] ?: return errorResponse(STATUS_NOT_FOUND, ErrorCode.NOT_FOUND)
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")
        val failure = planned[path]
        if (failure != null) {
            failure.remaining -= 1
            if (failure.remaining <= 0) planned.remove(path)
            return errorResponse(failure.status, failure.code, failure.retryAfterSeconds)
        }
        return try {
            val text = request.body?.utf8().orEmpty()
            handler(if (text.isEmpty()) JsonNull else ContractJson.parse(text), token)
        } catch (error: FakeServerError) {
            errorResponse(error.status, error.code)
        } catch (error: ContractViolation) {
            errorResponse(STATUS_INTERNAL, ErrorCode.INTERNAL)
        }
    }

    private fun userDto(user: StoredUser) =
        UserDto(user.id, user.username, user.role, user.status, null, 0, EPOCH_ISO, user.deletionDueAt)

    private fun issueSession(user: StoredUser, deviceLabel: String): SessionDto {
        tokenCounter += 1
        val token = "kdk_s_" + tokenCounter.toString().padStart(TOKEN_FILL_LENGTH, 'a')
        sessions[token] = user.id
        return SessionDto(token, ONE_DAY_ISO, deviceLabel)
    }

    private fun byId(userId: String): StoredUser =
        users.values.firstOrNull { it.id == userId } ?: throw FakeServerError(STATUS_NOT_FOUND, ErrorCode.NOT_FOUND)

    private fun sessionUser(token: String?): StoredUser {
        val userId = token?.let { sessions[it] } ?: throw FakeServerError(STATUS_UNAUTHORIZED, ErrorCode.SESSION_EXPIRED)
        return byId(userId)
    }

    private fun paramsFor(username: String): ParamsResponse {
        val known = users[username]
        return when {
            hostileParams -> ParamsResponse(encodeBytes(ByteArray(SALT_LENGTH) { 1 }), 1, HOSTILE_MEM_BYTES)
            known != null -> ParamsResponse(known.bundle.salt, known.bundle.opsLimit, known.bundle.memLimitBytes)
            else -> ParamsResponse(encodeBytes(ByteArray(SALT_LENGTH) { 2 }), FLOOR_OPS, ARGON2ID_MIN_MEM_BYTES)
        }
    }

    private fun ok(element: JsonElement) = jsonResponse(STATUS_OK, element)

    private val handlers: Map<String, (JsonElement, String?) -> MockResponse> = mapOf(
        "POST ${Routes.authParams()}" to { body, _ ->
            ok(ContractSchemas.paramsResponse.encode(paramsFor(ContractSchemas.paramsRequest.decode(body).username)))
        },
        "POST ${Routes.authSignUp()}" to { body, _ ->
            val request = ContractSchemas.signUpRequest.decode(body)
            signUpInvites.add(request.inviteToken)
            if (users.containsKey(request.username)) throw FakeServerError(STATUS_CONFLICT, ErrorCode.USERNAME_TAKEN)
            val user = StoredUser(
                id = request.userId,
                username = request.username,
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                deletionDueAt = null,
                loginKey = request.loginKey,
                recoveryLoginKey = request.recoveryLoginKey,
                bundle = KeyBundleDto(
                    request.salt,
                    request.opsLimit,
                    request.memLimitBytes,
                    request.masterKeyByKek,
                    request.recoveryKeyByMaster,
                    request.publicKey,
                    request.privateKeyByMaster,
                ),
                masterKeyByRecovery = request.masterKeyByRecovery,
            )
            users[user.username] = user
            ok(ContractSchemas.signUpResponse.encode(SignUpResponse(issueSession(user, request.deviceLabel), userDto(user))))
        },
        "POST ${Routes.authSignIn()}" to { body, _ ->
            val request = ContractSchemas.signInRequest.decode(body)
            val user = users[request.username]
            if (user == null || user.loginKey != request.loginKey) throw FakeServerError(STATUS_UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS)
            ok(ContractSchemas.signInResponse.encode(SignInResponse(issueSession(user, request.deviceLabel), userDto(user), user.bundle)))
        },
        "GET ${Routes.authSession()}" to { _, token ->
            ok(ContractSchemas.sessionResponse.encode(SessionResponse(userDto(sessionUser(token)))))
        },
        "GET ${Routes.authKeyBundle()}" to { _, token ->
            val user = sessionUser(token)
            if (user.status == UserStatus.PENDING_DELETION) throw FakeServerError(STATUS_FORBIDDEN, ErrorCode.ACCOUNT_PENDING_DELETION)
            ok(ContractSchemas.keyBundleDto.encode(user.bundle))
        },
        "POST ${Routes.authRecoveryStart()}" to { body, _ ->
            val request = ContractSchemas.recoveryStartRequest.decode(body)
            val user = users[request.username]
            val response = if (user == null) {
                RecoveryStartResponse(encodeBytes(ByteArray(SALT_LENGTH) { 3 }), encodeBytes(ByteArray(UNKNOWN_RECOVERY_WRAP_BYTES) { 4 }))
            } else {
                RecoveryStartResponse(user.id, user.masterKeyByRecovery)
            }
            ok(ContractSchemas.recoveryStartResponse.encode(response))
        },
        "POST ${Routes.authRecoveryComplete()}" to { body, _ ->
            val request = ContractSchemas.recoveryCompleteRequest.decode(body)
            val user = users[request.username]
            if (user == null || user.recoveryLoginKey != request.recoveryLoginKey) {
                throw FakeServerError(STATUS_UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS)
            }
            user.loginKey = request.loginKey
            user.bundle = user.bundle.copy(
                salt = request.salt,
                opsLimit = request.opsLimit,
                memLimitBytes = request.memLimitBytes,
                masterKeyByKek = request.masterKeyByKek,
            )
            sessions.clear()
            ok(ContractSchemas.signInResponse.encode(SignInResponse(issueSession(user, request.deviceLabel), userDto(user), user.bundle)))
        },
        "POST ${Routes.authSignOut()}" to { _, token ->
            if (token != null) sessions.remove(token)
            MockResponse.Builder().code(STATUS_NO_CONTENT).build()
        },
        "POST ${Routes.authPassword()}" to { body, token ->
            val user = sessionUser(token)
            val request = ContractSchemas.passwordChangeRequest.decode(body)
            if (request.currentLoginKey != user.loginKey) throw FakeServerError(STATUS_UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS)
            user.loginKey = request.loginKey
            user.bundle = user.bundle.copy(
                salt = request.salt,
                opsLimit = request.opsLimit,
                memLimitBytes = request.memLimitBytes,
                masterKeyByKek = request.masterKeyByKek,
            )
            sessions.clear()
            ok(ContractSchemas.passwordChangeResponse.encode(PasswordChangeResponse(issueSession(user, DEVICE_LABEL))))
        },
        "POST ${Routes.usersMeDelete()}" to { _, token ->
            val user = sessionUser(token)
            user.status = UserStatus.PENDING_DELETION
            user.deletionDueAt = ONE_DAY_ISO
            sessions.clear()
            ok(ContractSchemas.deletionRequestResponse.encode(DeletionRequestResponse(ONE_DAY_ISO)))
        },
        "POST ${Routes.usersMeDeleteCancel()}" to { _, token ->
            val user = sessionUser(token)
            user.status = UserStatus.ACTIVE
            user.deletionDueAt = null
            ok(ContractSchemas.userDto.encode(userDto(user)))
        },
    )

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TOKEN_FILL_LENGTH = 43
        const val HOSTILE_MEM_BYTES = 1024L
        const val UNKNOWN_RECOVERY_WRAP_BYTES = 73
        const val STATUS_OK = 200
        const val STATUS_NO_CONTENT = 204
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_FORBIDDEN = 403
        const val STATUS_NOT_FOUND = 404
        const val STATUS_CONFLICT = 409
        const val STATUS_INTERNAL = 500
    }
}

class DeviceContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
}

class MemorySessionStore : SessionStorePort {
    private val tokens = HashMap<String, String>()

    @Synchronized
    override fun save(accountKey: String, token: String) {
        tokens[accountKey] = token
    }

    @Synchronized
    override fun load(accountKey: String): String? = tokens[accountKey]

    @Synchronized
    override fun clear(accountKey: String) {
        tokens.remove(accountKey)
    }
}

class MemoryGlobalStore : GlobalStorePort {
    private var serverUrl: String? = null
    private var activeAccount: ActiveAccount? = null
    private var serverConfigJson: String? = null

    @Synchronized
    override fun serverUrl(): String? = serverUrl

    @Synchronized
    override fun setServerUrl(url: String) {
        serverUrl = url
    }

    @Synchronized
    override fun clearServerUrl() {
        serverUrl = null
    }

    @Synchronized
    override fun activeAccount(): ActiveAccount? = activeAccount

    @Synchronized
    override fun setActiveAccount(account: ActiveAccount) {
        activeAccount = account
    }

    @Synchronized
    override fun clearActiveAccount() {
        activeAccount = null
    }

    @Synchronized
    override fun serverConfigJson(): String? = serverConfigJson

    @Synchronized
    override fun setServerConfigJson(json: String) {
        serverConfigJson = json
    }

    @Synchronized
    override fun clearServerConfigJson() {
        serverConfigJson = null
    }
}

class MemoryKeyVault(private val loadOverride: (() -> ByteArray?)? = null) : KeyVaultPort {
    private val keys = HashMap<String, ByteArray>()
    val forgetCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val storeCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var forgetFailure: Throwable? = null

    @Synchronized
    override fun storeMasterKey(accountKey: String, masterKey: ByteArray) {
        storeCalls.add(accountKey)
        keys.remove(accountKey)?.let(::wipe)
        keys[accountKey] = masterKey.copyOf()
    }

    @Synchronized
    override fun loadMasterKey(accountKey: String): ByteArray? {
        val override = loadOverride
        if (override != null) return override()
        return keys[accountKey]?.copyOf()
    }

    @Synchronized
    override fun forget(accountKey: String) {
        forgetCalls.add(accountKey)
        forgetFailure?.let { throw it }
        keys.remove(accountKey)?.let(::wipe)
    }
}

class SpyRowStore(private val delegate: RowStorePort) : RowStorePort by delegate {
    val destroyCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var destroyFailure: Throwable? = null

    @Volatile
    var nextKvSetJsonFailure: Throwable? = null

    @Volatile
    var nextKvGetJsonFailure: Throwable? = null

    override fun destroy(accountKey: String) {
        destroyCalls.add(accountKey)
        destroyFailure?.let { throw it }
        delegate.destroy(accountKey)
    }

    override fun kvSetJson(key: String, value: JsonElement) {
        nextKvSetJsonFailure?.let {
            nextKvSetJsonFailure = null
            throw it
        }
        delegate.kvSetJson(key, value)
    }

    override fun kvGetJson(key: String): JsonElement? {
        nextKvGetJsonFailure?.let {
            nextKvGetJsonFailure = null
            throw it
        }
        return delegate.kvGetJson(key)
    }
}

class SpyFileStore(private val delegate: FileStorePort) : FileStorePort by delegate {
    val destroyCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun destroy(accountKey: String) {
        destroyCalls.add(accountKey)
        delegate.destroy(accountKey)
    }
}

class TestDevice(base: Context, index: Int) {
    private val hasher = createSodium()
    val context: Context = DeviceContext(base, File(base.filesDir, "device-$index"))
    val rowStore = SpyRowStore(DatabaseRowStore(context, hasher))
    val fileStore = SpyFileStore(DiskFileStore(context, hasher))
    val sessionStore = MemorySessionStore()
    val globalStore = MemoryGlobalStore()

    fun databaseFile(accountKey: String): File = KodakikoDatabase.databaseFile(context, accountHash(hasher, accountKey))

    fun accountRoot(accountKey: String): File = AccountPaths(context, accountHash(hasher, accountKey)).root
}

class FakeServerUrl(private val currentUrl: String?) : ServerUrlStore {
    @Volatile
    var chooseResult: ServerChoice? = null

    val chosen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var clearCalls = 0
        private set

    override suspend fun current(): String? = currentUrl

    override suspend fun choose(input: String): ServerChoice {
        chosen.add(input)
        return chooseResult ?: error("no choice planned")
    }

    override suspend fun clear() {
        clearCalls += 1
    }
}

class RecordingConfigService : ConfigService {
    val accepted: MutableList<PublicConfigDto> = Collections.synchronizedList(mutableListOf())

    override fun current(): PublicConfigDto? = null

    override suspend fun refresh(): PublicConfigDto? = null

    override fun accept(config: PublicConfigDto) {
        accepted.add(config)
    }

    override fun subscribe(listener: (PublicConfigDto) -> Unit): () -> Unit = {}
}

class FakeLifecycle : LifecyclePort {
    val activeListeners: MutableList<(Boolean) -> Unit> = Collections.synchronizedList(mutableListOf())
    var onAfterInteractions: () -> Unit = {}

    @Volatile
    var afterInteractionsCalls = 0
        private set

    override suspend fun afterInteractions() {
        afterInteractionsCalls += 1
        onAfterInteractions()
    }

    override fun subscribeActive(listener: (Boolean) -> Unit): () -> Unit {
        activeListeners.add(listener)
        return { activeListeners.remove(listener) }
    }
}

class TestApp(
    val server: FakeAuthServer,
    val device: TestDevice,
    val sodium: TestSodium,
    val keyVault: MemoryKeyVault,
    currentServerUrl: String?,
    dispatcher: CoroutineDispatcher,
) {
    val accountState: Observable<AccountState> = Observable(AccountState.SignedOut())
    val activeAccountKey: Observable<String?> = Observable(null)
    val inviteToken: Observable<String?> = Observable(null)
    val serverUrl = FakeServerUrl(currentServerUrl)
    val config = RecordingConfigService()
    val lifecycle = FakeLifecycle()
    var onShowSecuring: () -> Unit = {}
    var beforeSignOut: suspend () -> Unit = {}
    var recoveryWords: List<String> = emptyList()

    @Volatile
    var showSecuringCalls = 0
        private set

    @Volatile
    var dismissSecuringCalls = 0
        private set

    val http = HttpClient(
        baseUrl = { server.baseUrl },
        sessionToken = ::currentSessionToken,
        onSessionInvalid = { accountState.value = AccountState.SignedOut() },
        sleep = {},
        okHttpClient = sharedOkHttp,
    )

    val service: AuthService = DefaultAuthService(
        AuthServiceDeps(
            sodium = sodium,
            http = http,
            serverUrl = serverUrl,
            sessionStore = device.sessionStore,
            keyVault = keyVault,
            rowStore = device.rowStore,
            fileStore = device.fileStore,
            globalStore = device.globalStore,
            lifecycle = lifecycle,
            nowIso = { EPOCH_ISO },
            randomId = randomIdOf(sodium),
            accountState = accountState,
            activeAccountKey = activeAccountKey,
            config = config,
            inviteToken = inviteToken,
            deviceLabel = { DEVICE_LABEL },
            homeCollectionName = HOME_COLLECTION_NAME,
            kdfStartParams = ::floorParams,
            showSecuring = {
                showSecuringCalls += 1
                onShowSecuring()
            },
            dismissSecuring = { dismissSecuringCalls += 1 },
            beforeSignOut = suspend { beforeSignOut() },
            ioDispatcher = dispatcher,
        ),
    )

    private fun currentSessionToken(): String? {
        val accountKey: String = activeAccountKey.value ?: return null
        return device.sessionStore.load(accountKey)
    }

    fun liveKeys(): List<ByteArray> {
        val state = accountState.value as? AccountState.Unlocked ?: error("expected unlocked")
        return listOf(state.keys.masterKey, state.keys.privateKey)
    }

    fun unlocked(): AccountState.Unlocked = accountState.value as? AccountState.Unlocked ?: error("expected unlocked")

    fun accountKey(): String = activeAccountKey.value ?: error("expected an account key")
}

fun TestScope.startApp(
    server: FakeAuthServer,
    device: TestDevice,
    sodium: TestSodium,
    keyVault: MemoryKeyVault = MemoryKeyVault(),
    noServer: Boolean = false,
    baseUrl: String = BASE_URL,
): TestApp = TestApp(server, device, sodium, keyVault, if (noServer) null else baseUrl, UnconfinedTestDispatcher(testScheduler))

suspend fun TestScope.signUpAlice(server: FakeAuthServer, device: TestDevice, sodium: TestSodium): TestApp {
    val app = startApp(server, device, sodium)
    app.recoveryWords = app.service.signUp(username = USERNAME, password = PASSWORD, inviteToken = null)
    return app
}

suspend fun TestScope.localAlice(server: FakeAuthServer, device: TestDevice, sodium: TestSodium): TestApp {
    val app = startApp(server, device, sodium, noServer = true)
    app.accountState.value = AccountState.NoServer
    app.recoveryWords = app.service.setUpLocal(username = USERNAME, password = PASSWORD)
    return app
}

fun accountKeyOf(server: FakeAuthServer): String = "$HOST/${server.user(USERNAME).id}"

abstract class AccountTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    private val servers = mutableListOf<FakeAuthServer>()
    private var devices = 0

    protected fun createFakeAuthServer(): FakeAuthServer = FakeAuthServer().start().also { servers.add(it) }

    protected fun createDevice(): TestDevice = TestDevice(context, ++devices)

    @After
    fun shutDownServers() {
        servers.forEach { it.shutdown() }
    }
}
