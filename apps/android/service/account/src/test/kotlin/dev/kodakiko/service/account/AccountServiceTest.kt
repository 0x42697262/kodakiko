@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.ErrorCode
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.UserDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val user = UserDto(
        id = "U".repeat(22),
        username = "ada",
        role = UserRole.ADMIN,
        status = UserStatus.ACTIVE,
        quotaBytes = null,
        usedBytes = 10,
        createdAt = "2026-06-01T10:00:00.000Z",
        deletionDueAt = null,
    )
    private val server = MockWebServer()

    @Volatile
    private var online = true

    private lateinit var rowStore: RowStorePort

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!online) return disconnectResponse()
                if (request.method != "GET" || request.url.encodedPath != Routes.usersMe()) return errorResponse(404, ErrorCode.NOT_FOUND)
                return jsonResponse(200, ContractSchemas.userDto.encode(user))
            }
        }
        server.start()
        rowStore = DatabaseRowStore(context, createSodium())
        rowStore.open("account")
    }

    @After
    fun stop() = server.close()

    private fun TestScope.service(): AccountService =
        DefaultAccountService(
            http = HttpClient(
                baseUrl = { server.url("/").toString().trimEnd('/') },
                sessionToken = { null },
                onSessionInvalid = {},
                sleep = {},
                okHttpClient = sharedOkHttp,
            ),
            rowStore = rowStore,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    @Test
    fun refreshMeStoresTheRoleAndDeletionDateAndKeepsTheUserInMemory() = runTest {
        val service = service()
        val refreshed = service.refreshMe()
        assertEquals("ada", refreshed?.username)
        assertEquals(JsonPrimitive("admin"), rowStore.kvGetJson(StorageKeys.ACCOUNT_ROLE))
        assertEquals(10L, service.user.value?.usedBytes)
        assertTrue(service.isAdmin())
    }

    @Test
    fun keepsTheLastValuesWhenOffline() = runTest {
        val service = service()
        service.refreshMe()
        online = false
        assertNull(service.refreshMe())
        assertEquals("ada", service.user.value?.username)
        assertTrue(service.isAdmin())
    }

    @Test
    fun refreshMeClearsAStoredDeletionDateAndClearForgetsTheUser() = runTest {
        rowStore.kvSetJson(StorageKeys.ACCOUNT_DELETION_DUE_AT, JsonPrimitive("2026-07-01T10:00:00.000Z"))
        val service = service()
        service.refreshMe()
        assertNull(rowStore.kvGetJson(StorageKeys.ACCOUNT_DELETION_DUE_AT))
        service.clear()
        assertNull(service.user.value)
    }
}
