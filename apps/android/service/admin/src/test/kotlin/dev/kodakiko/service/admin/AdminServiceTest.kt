@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.admin

import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminInviteCreateResponse
import dev.kodakiko.contract.AdminStatsResponse
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.AdminUserListResponse
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorBody
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.ErrorResponse
import dev.kodakiko.contract.InviteDto
import dev.kodakiko.contract.InviteListResponse
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UserDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.net.NetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private const val TOKEN = "session-token"
private const val NOW = "2026-06-01T10:00:00.000Z"
private const val LATER = "2026-06-08T10:00:00.000Z"
private val USER = UserDto(
    id = "U".repeat(22),
    username = "ada",
    role = UserRole.ADMIN,
    status = UserStatus.ACTIVE,
    quotaBytes = null,
    usedBytes = 10,
    createdAt = NOW,
    deletionDueAt = null,
)
private val INVITE_ID = "I".repeat(22)
private val STATS = AdminStatsResponse(
    users = 2,
    activeUsers30d = 1,
    pendingDeletion = 0,
    collections = 3,
    entries = 4,
    objects = 5,
    totalBytes = 6,
    quotaBytesAllocated = null,
)

private data class Seen(val method: String, val path: String, val query: String?, val body: String, val authorization: String?)

private fun json(status: Int, element: JsonElement): MockResponse =
    MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(ContractJson.stringify(element)).build()

private fun failure(status: Int, code: ErrorCode): MockResponse =
    json(status, ContractSchemas.errorResponseSchema.encode(ErrorResponse(ErrorBody(code, code.wire, "req-admin", JsonNull))))

private fun dropped(): MockResponse = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

class AdminServiceTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<Seen>()
    private val callThreads = CopyOnWriteArrayList<String>()
    private val handlers = HashMap<String, (Seen) -> MockResponse>()
    private val dropsLeft = HashMap<String, Int>()
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                callThreads += Thread.currentThread().name
                chain.proceed(chain.request())
            },
        )
        .build()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val call = Seen(request.method, request.url.encodedPath, request.url.encodedQuery, request.body?.utf8().orEmpty(), request.headers["Authorization"])
                seen += call
                val key = "${call.method} ${call.path}"
                synchronized(dropsLeft) {
                    val left = dropsLeft[key] ?: 0
                    if (left > 0) {
                        dropsLeft[key] = left - 1
                        return dropped()
                    }
                }
                val handler = synchronized(handlers) { handlers[key] } ?: return failure(404, ErrorCode.NOT_FOUND)
                return handler(call)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun on(method: String, path: String, handler: (Seen) -> MockResponse) {
        synchronized(handlers) { handlers["$method $path"] = handler }
    }

    private fun dropNext(method: String, path: String, times: Int) {
        synchronized(dropsLeft) { dropsLeft["$method $path"] = times }
    }

    private fun http(): HttpClient =
        HttpClient(
            baseUrl = { server.url("/").toString().trimEnd('/') },
            sessionToken = { TOKEN },
            onSessionInvalid = {},
            sleep = {},
            okHttpClient = okHttp,
        )

    private fun admin(io: CoroutineDispatcher): AdminService = DefaultAdminService(http = http(), ioDispatcher = io)

    private fun bodyOf(call: Seen): JsonObject = Json.parseToJsonElement(call.body).jsonObject

    private suspend inline fun <reified T : Throwable> failureOf(crossinline block: suspend () -> Unit): T {
        val caught: Throwable? = try {
            block()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error
        }
        assertTrue("expected ${T::class.java.simpleName}, got $caught", caught is T)
        return caught as T
    }

    @Test
    fun wrapsTheAdminRoutes() = runTest {
        on("GET", Routes.adminStats()) { json(200, ContractSchemas.adminStatsResponse.encode(STATS)) }
        on("PATCH", Routes.adminUser(USER.id)) { call: Seen ->
            val patch: AdminUserPatchRequest = ContractSchemas.adminUserPatchRequest.decode(bodyOf(call))
            val quota = (patch.quotaBytes as? OptionalValue.Present<Long?>)?.value ?: USER.quotaBytes
            json(200, ContractSchemas.adminUserDto.encode(AdminUserDto(USER.copy(quotaBytes = quota), sessionCount = 1, lastActiveAt = null)))
        }
        val admin = admin(UnconfinedTestDispatcher(testScheduler))

        assertEquals(4L, admin.stats().entries)
        val patched: AdminUserDto = admin.patchUser(USER.id, AdminUserPatchRequest(quotaBytes = OptionalValue.Present(1024L)))
        assertEquals(1024L, patched.user.quotaBytes)
    }

    @Test
    fun statsReturnsEveryServerCounter() = runTest {
        on("GET", Routes.adminStats()) { json(200, ContractSchemas.adminStatsResponse.encode(STATS.copy(quotaBytesAllocated = 4096))) }
        val stats: AdminStatsResponse = admin(UnconfinedTestDispatcher(testScheduler)).stats()
        assertEquals(STATS.copy(quotaBytesAllocated = 4096), stats)
        val call = seen.single()
        assertEquals("GET", call.method)
        assertEquals(Routes.adminStats(), call.path)
        assertEquals("Bearer $TOKEN", call.authorization)
    }

    @Test
    fun patchUserSendsOnlyTheChangedFields() = runTest {
        on("PATCH", Routes.adminUser(USER.id)) { json(200, ContractSchemas.adminUserDto.encode(AdminUserDto(USER.copy(role = UserRole.MEMBER), 2, NOW))) }
        val admin = admin(UnconfinedTestDispatcher(testScheduler))

        val demoted: AdminUserDto = admin.patchUser(USER.id, AdminUserPatchRequest(role = UserRole.MEMBER))
        admin.patchUser(USER.id, AdminUserPatchRequest(quotaBytes = OptionalValue.Present(null)))

        assertEquals(UserRole.MEMBER, demoted.user.role)
        assertEquals(2L, demoted.sessionCount)
        assertEquals(NOW, demoted.lastActiveAt)
        val (first, second) = seen.toList()
        assertEquals("PATCH", first.method)
        assertEquals(Routes.adminUser(USER.id), first.path)
        assertEquals(JsonObject(mapOf("role" to JsonPrimitive("member"))), bodyOf(first))
        assertEquals(JsonObject(mapOf("quotaBytes" to JsonNull)), bodyOf(second))
    }

    @Test
    fun listUsersPassesTheCursorUriEncoded() = runTest {
        val page = AdminUserListResponse(listOf(AdminUserDto(USER, 1, null)), nextCursor = "next/page")
        on("GET", Routes.adminUsers()) { json(200, ContractSchemas.adminUserListResponse.encode(page)) }
        val admin = admin(UnconfinedTestDispatcher(testScheduler))

        val first: AdminUserListResponse = admin.listUsers(null)
        admin.listUsers("a/b c+d")

        assertEquals(page, first)
        assertEquals(listOf(Routes.adminUsers(), Routes.adminUsers()), seen.map { it.path })
        assertNull(seen[0].query)
        assertEquals("cursor=a%2Fb%20c%2Bd", seen[1].query)
    }

    @Test
    fun createInvitePostsTheRoleAndExpiryAndReturnsTheInviteUrl() = runTest {
        on("POST", Routes.adminInvites()) { json(201, ContractSchemas.adminInviteCreateResponse.encode(AdminInviteCreateResponse("https://journal.example/invite#abc", LATER))) }
        val created: AdminInviteCreateResponse = admin(UnconfinedTestDispatcher(testScheduler)).createInvite(AdminInviteCreateRequest(UserRole.MEMBER, 7))

        assertEquals(AdminInviteCreateResponse("https://journal.example/invite#abc", LATER), created)
        val call = seen.single()
        assertEquals("POST", call.method)
        assertEquals(Routes.adminInvites(), call.path)
        assertEquals(JsonObject(mapOf("role" to JsonPrimitive("member"), "expiresInDays" to JsonPrimitive(7))), bodyOf(call))
    }

    @Test
    fun createInviteIsNotRetriedAfterADroppedConnectionButReadsAre() = runTest {
        val invites = InviteListResponse(listOf(InviteDto(INVITE_ID, UserRole.MEMBER, NOW, LATER, null, null, null)))
        on("POST", Routes.adminInvites()) { json(201, ContractSchemas.adminInviteCreateResponse.encode(AdminInviteCreateResponse("https://journal.example/invite#abc", LATER))) }
        on("GET", Routes.adminInvites()) { json(200, ContractSchemas.inviteListResponse.encode(invites)) }
        dropNext("POST", Routes.adminInvites(), 1)
        dropNext("GET", Routes.adminInvites(), 1)
        val admin = admin(UnconfinedTestDispatcher(testScheduler))

        failureOf<NetworkError> { admin.createInvite(AdminInviteCreateRequest(UserRole.ADMIN, 1)) }
        val listed: InviteListResponse = admin.listInvites()

        assertEquals(1, seen.count { it.method == "POST" })
        assertEquals(2, seen.count { it.method == "GET" })
        assertEquals(invites, listed)
    }

    @Test
    fun listInvitesReturnsRedeemedAndRevokedInvites() = runTest {
        val invites = InviteListResponse(
            listOf(
                InviteDto(INVITE_ID, UserRole.MEMBER, NOW, LATER, NOW, null, "bob"),
                InviteDto("J".repeat(22), UserRole.ADMIN, NOW, LATER, null, NOW, null),
            ),
        )
        on("GET", Routes.adminInvites()) { json(200, ContractSchemas.inviteListResponse.encode(invites)) }
        assertEquals(invites, admin(UnconfinedTestDispatcher(testScheduler)).listInvites())
        assertEquals("GET", seen.single().method)
    }

    @Test
    fun revokeInviteDeletesTheInviteAndAcceptsAnEmptyResponse() = runTest {
        on("DELETE", Routes.adminInvite(INVITE_ID)) { MockResponse.Builder().code(204).build() }
        admin(UnconfinedTestDispatcher(testScheduler)).revokeInvite(INVITE_ID)
        val call = seen.single()
        assertEquals("DELETE", call.method)
        assertEquals(Routes.adminInvite(INVITE_ID), call.path)
        assertEquals("Bearer $TOKEN", call.authorization)
    }

    @Test
    fun aServerRefusalSurfacesAsApiErrorWithItsCode() = runTest {
        on("GET", Routes.adminStats()) { failure(403, ErrorCode.FORBIDDEN) }
        on("PATCH", Routes.adminUser(USER.id)) { failure(409, ErrorCode.VALIDATION_FAILED) }
        val admin = admin(UnconfinedTestDispatcher(testScheduler))

        val forbidden = failureOf<ApiError> { admin.stats() }
        val refused = failureOf<ApiError> { admin.patchUser(USER.id, AdminUserPatchRequest(role = UserRole.MEMBER)) }

        assertEquals(403, forbidden.status)
        assertEquals(ErrorCode.FORBIDDEN, forbidden.code)
        assertEquals(ErrorCode.VALIDATION_FAILED, refused.code)
    }

    @Test
    fun callsRunOnTheInjectedIoDispatcher() = runTest {
        on("GET", Routes.adminStats()) { json(200, ContractSchemas.adminStatsResponse.encode(STATS)) }
        on("GET", Routes.adminInvites()) { json(200, ContractSchemas.inviteListResponse.encode(InviteListResponse(emptyList()))) }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "admin-io-test") }
        val io: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
        try {
            val admin = admin(io)
            admin.stats()
            admin.listInvites()
        } finally {
            io.close()
        }
        assertEquals(listOf("admin-io-test", "admin-io-test"), callThreads.toList())
    }
}
