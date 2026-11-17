package dev.kodakiko.ui.feature.settings

import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminInviteCreateResponse
import dev.kodakiko.contract.AdminStatsResponse
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.AdminUserListResponse
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.InviteDto
import dev.kodakiko.contract.InviteListResponse
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.UserDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.crypto.VectorCaseResult
import dev.kodakiko.index.GalleryIndex
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.admin.AdminService
import dev.kodakiko.service.diagnostics.DiagnosticsService
import dev.kodakiko.service.diagnostics.VectorReport
import dev.kodakiko.service.index.IndexBuildReport
import dev.kodakiko.service.index.IndexService
import dev.kodakiko.service.index.IndexStatus
import dev.kodakiko.service.index.IndexVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val USERNAME = "ada"
const val USER_ID = "USER1"
const val SECURITY_CODE = "1234 5678 9012 3456"
const val SERVER_URL = "https://kodakiko.example.com"
const val NOW_MS = 1_795_000_000_000L
const val NOW_TEXT = "2026-11-18 11:06"
const val DEFAULT_QUOTA_FALLBACK = 107_374_182_400L
const val APP_VERSION = "0.4.0"
const val GOOD_PASSWORD = "correct horse battery staple"
const val NEW_PASSWORD = "a brand new passphrase"
const val DEBUG_JSON = "{\n  \"app\": \"0.4.0\",\n  \"platform\": \"android\"\n}"

val RECOVERY_WORDS: List<String> = List(24) { "word${it + 1}" }

fun userDto(
    id: String = USER_ID,
    username: String = USERNAME,
    role: UserRole = UserRole.MEMBER,
    status: UserStatus = UserStatus.ACTIVE,
    quotaBytes: Long? = null,
    usedBytes: Long = 340 * 1024L,
): UserDto = UserDto(
    id = id,
    username = username,
    role = role,
    status = status,
    quotaBytes = quotaBytes,
    usedBytes = usedBytes,
    createdAt = "2026-01-01T00:00:00.000Z",
    deletionDueAt = null,
)

fun adminUser(
    id: String,
    username: String,
    role: UserRole = UserRole.MEMBER,
    quotaBytes: Long? = null,
    usedBytes: Long = GB,
    status: UserStatus = UserStatus.ACTIVE,
    lastActiveAt: String? = "2026-11-10T09:30:00.000Z",
): AdminUserDto = AdminUserDto(userDto(id, username, role, status, quotaBytes, usedBytes), sessionCount = 1, lastActiveAt = lastActiveAt)

fun invite(
    id: String,
    role: UserRole = UserRole.MEMBER,
    expiresAt: String = "2026-11-25T00:00:00.000Z",
    redeemedAt: String? = null,
    revokedAt: String? = null,
    redeemedBy: String? = null,
): InviteDto = InviteDto(id, role, "2026-11-18T00:00:00.000Z", expiresAt, redeemedAt, revokedAt, redeemedBy)

class FakeSettingsProfile : SettingsProfile {
    val usernameState = MutableStateFlow(USERNAME)
    val unlockedState = MutableStateFlow(true)
    val userState = MutableStateFlow<UserDto?>(userDto())
    var url: String? = SERVER_URL
    var code: String = SECURITY_CODE
    var facts: AccountFacts? = AccountFacts(USER_ID, USERNAME, 16, 268_435_456, true)
    var storage = KeyStorage(persistent = true, biometric = true)
    var storageFailure: Throwable? = null
    var refreshResult: UserDto? = userDto()
    var refreshFailure: Throwable? = null
    val calls = mutableListOf<String>()

    override val username: StateFlow<String> = usernameState.asStateFlow()

    override val unlocked: StateFlow<Boolean> = unlockedState.asStateFlow()

    override val user: StateFlow<UserDto?> = userState.asStateFlow()

    override suspend fun refreshMe(): UserDto? {
        calls += REFRESH
        refreshFailure?.let { throw it }
        refreshResult?.let { userState.value = it }
        return refreshResult
    }

    override suspend fun serverUrl(): String? {
        calls += SERVER_URL_CALL
        return url
    }

    override fun securityCode(): String {
        calls += SECURITY_CODE_CALL
        return code
    }

    override suspend fun accountFacts(): AccountFacts? = facts

    override suspend fun keyStorage(): KeyStorage {
        storageFailure?.let { throw it }
        return storage
    }

    companion object {
        const val REFRESH = "refreshMe"
        const val SERVER_URL_CALL = "serverUrl"
        const val SECURITY_CODE_CALL = "securityCode"
    }
}

class FakeSettingsCredentials : SettingsCredentials {
    val calls = mutableListOf<String>()
    val passwords = mutableListOf<String>()
    var password = GOOD_PASSWORD
    var words: List<String> = RECOVERY_WORDS
    var changeFailure: Throwable? = null
    var revealFailure: Throwable? = null
    var deletionFailure: Throwable? = null
    var verifyFailure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var revealGate: CompletableDeferred<Unit>? = null

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        calls += "$CHANGE_PASSWORD:$currentPassword:$newPassword"
        gate?.await()
        changeFailure?.let { throw it }
        password = newPassword
    }

    override suspend fun revealRecoveryWords(password: String): List<String> {
        calls += REVEAL
        passwords += password
        revealGate?.await()
        revealFailure?.let { throw it }
        if (password != this.password) throw AuthError(AuthFailure.WRONG_PASSWORD)
        return words
    }

    override suspend fun verifyPassword(password: String): Boolean {
        calls += VERIFY
        passwords += password
        gate?.await()
        verifyFailure?.let { throw it }
        return password == this.password
    }

    override suspend fun requestAccountDeletion() {
        calls += DELETE
        deletionFailure?.let { throw it }
    }

    override suspend fun eraseLocal() {
        calls += ERASE
    }

    override suspend fun changeServer() {
        calls += CHANGE_SERVER
    }

    companion object {
        const val CHANGE_PASSWORD = "changePassword"
        const val REVEAL = "revealRecoveryWords"
        const val VERIFY = "verifyPassword"
        const val DELETE = "requestAccountDeletion"
        const val ERASE = "eraseLocal"
        const val CHANGE_SERVER = "changeServer"
    }
}

class FakeIndexService : IndexService {
    val versionState = MutableStateFlow(IndexVersion(IndexStatus.READY, 1, 1))
    val calls = mutableListOf<String>()
    var report = IndexBuildReport(entries = 42, unreadable = 1, durationMs = 137)
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override val version: StateFlow<IndexVersion> = versionState.asStateFlow()

    override suspend fun build(): IndexBuildReport {
        calls += BUILD
        gate?.await()
        failure?.let { throw it }
        return report
    }

    override fun index(): GalleryIndex? = null

    override fun status(): IndexStatus = versionState.value.status

    override fun subscribe(listener: () -> Unit): () -> Unit = {}

    override fun clear() {
        calls += CLEAR
    }

    companion object {
        const val BUILD = "build"
        const val CLEAR = "clear"
    }
}

class FakeDiagnosticsService : DiagnosticsService {
    var report = VectorReport(
        listOf(
            VectorCaseResult("kdf.json", "argon2id basic", passed = true, durationMs = 812),
            VectorCaseResult("aead.json", "xchacha20 seal", passed = true, durationMs = 3),
            VectorCaseResult("stream.json", "stream final chunk", passed = false, durationMs = 11),
        ),
    )
    var json = DEBUG_JSON
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var runs = 0
    var debugCalls = 0

    override suspend fun runVectors(): VectorReport {
        runs += 1
        gate?.await()
        failure?.let { throw it }
        return report
    }

    override fun debugInfoJson(): String {
        debugCalls += 1
        return json
    }
}

class FakeAdminService : AdminService {
    val users = mutableListOf(
        adminUser("U1", USERNAME, role = UserRole.ADMIN, quotaBytes = 10 * GB, usedBytes = GB),
        adminUser("U2", "bob", usedBytes = 120 * GB, lastActiveAt = null),
    )
    val invites = mutableListOf<InviteDto>()
    var pageSize = Int.MAX_VALUE
    val cursors = mutableListOf<String?>()
    val patches = mutableListOf<Pair<String, AdminUserPatchRequest>>()
    val created = mutableListOf<AdminInviteCreateRequest>()
    val revoked = mutableListOf<String>()
    var stats = AdminStatsResponse(
        users = 12,
        activeUsers30d = 7,
        pendingDeletion = 1,
        collections = 30,
        entries = 4_200,
        objects = 9_001,
        totalBytes = 5 * GB,
        quotaBytesAllocated = 100 * GB,
    )
    var listFailure: Throwable? = null
    var patchFailure: Throwable? = null
    var inviteFailure: Throwable? = null
    var statsFailure: Throwable? = null
    var nextInviteUrl = "https://kodakiko.example.com/invite#kdk_i_abcdefghijklmnop"
    val calls = mutableListOf<String>()

    override suspend fun listUsers(cursor: String?): AdminUserListResponse {
        calls += LIST_USERS
        cursors += cursor
        listFailure?.let { throw it }
        val start = cursor?.toInt() ?: 0
        val end = minOf(users.size, start + pageSize)
        return AdminUserListResponse(users.subList(start, end).toList(), if (end < users.size) end.toString() else null)
    }

    override suspend fun patchUser(id: String, patch: AdminUserPatchRequest): AdminUserDto {
        calls += PATCH
        patches += id to patch
        patchFailure?.let { throw it }
        val index = users.indexOfFirst { it.user.id == id }
        val current = users[index]
        val quota = when (val value = patch.quotaBytes) {
            OptionalValue.Absent -> current.user.quotaBytes
            is OptionalValue.Present -> value.value
        }
        val updated = current.copy(user = current.user.copy(quotaBytes = quota, role = patch.role ?: current.user.role))
        users[index] = updated
        return updated
    }

    override suspend fun createInvite(input: AdminInviteCreateRequest): AdminInviteCreateResponse {
        calls += CREATE_INVITE
        created += input
        inviteFailure?.let { throw it }
        invites += invite("I${invites.size + 1}", role = input.role)
        return AdminInviteCreateResponse(nextInviteUrl, "2026-11-25T00:00:00.000Z")
    }

    override suspend fun listInvites(): InviteListResponse {
        calls += LIST_INVITES
        inviteFailure?.let { throw it }
        return InviteListResponse(invites.toList())
    }

    override suspend fun revokeInvite(id: String) {
        calls += REVOKE
        revoked += id
        inviteFailure?.let { throw it }
        val index = invites.indexOfFirst { it.id == id }
        invites[index] = invites[index].copy(revokedAt = "2026-11-18T09:00:00.000Z")
    }

    override suspend fun stats(): AdminStatsResponse {
        calls += STATS
        statsFailure?.let { throw it }
        return stats
    }

    companion object {
        const val LIST_USERS = "listUsers"
        const val PATCH = "patchUser"
        const val CREATE_INVITE = "createInvite"
        const val LIST_INVITES = "listInvites"
        const val REVOKE = "revokeInvite"
        const val STATS = "stats"
    }
}

class FakeAdminShare : AdminShare {
    val shared = mutableListOf<String>()

    override fun share(text: String) {
        shared += text
    }
}

class FakeSettingsLicenses(var licenses: List<OpenSourceLicense> = DEFAULT_LICENSES) : SettingsLicenses {
    var loads = 0

    override suspend fun load(): List<OpenSourceLicense> {
        loads += 1
        return licenses
    }

    companion object {
        val DEFAULT_LICENSES = listOf(
            OpenSourceLicense("Voyager", "2.2.21", "MIT"),
            OpenSourceLicense("OkHttp", "5.3.0", "Apache-2.0"),
            OpenSourceLicense("Coil", "3.3.0", "Apache-2.0"),
        )
    }
}
