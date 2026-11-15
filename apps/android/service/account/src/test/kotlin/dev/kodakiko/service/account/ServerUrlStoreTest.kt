@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.account

import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SignupMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections

class ServerUrlStoreTest {
    private val validConfig: PublicConfigDto = config(
        signupMode = SignupMode.INVITE,
        maxMediaPerEntry = 20,
        maxUploadBytes = 1024,
        defaultQuotaBytes = 0,
        trashDays = 30,
        accountDeletionGraceDays = 14,
        serverVersion = "0.1.0",
    )
    private val inviteToken = "kdk_i_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    private class Built(val store: ServerUrlStore, val requested: List<String>, val globalStore: MemoryGlobalStore) {
        fun stored(): String? = globalStore.serverUrl()
    }

    private fun response(request: Request, status: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun TestScope.buildStore(
        allowLocalHttp: Boolean = false,
        timeoutMs: Long = 1000,
        handler: (Interceptor.Chain) -> Response,
    ): Built {
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val globalStore = MemoryGlobalStore()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    requested.add(chain.request().url.toString())
                    handler(chain)
                },
            )
            .build()
        val store = HttpServerUrlStore(
            okHttpClient = client,
            globalStore = globalStore,
            timeoutMs = timeoutMs,
            allowLocalHttp = allowLocalHttp,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return Built(store, requested, globalStore)
    }

    private fun ok(chain: Interceptor.Chain): Response = response(chain.request(), 200, configJson(validConfig))

    @Test
    fun prefixesABareHostWithHttpsAndStoresTheOrigin() = runTest {
        val built = buildStore(handler = ::ok)
        assertEquals(ServerChoice.Ok("https://example.com", validConfig, null), built.store.choose("example.com"))
        assertEquals(listOf("https://example.com${Routes.config()}"), built.requested.toList())
        assertEquals("https://example.com", built.stored())
        assertEquals("https://example.com", built.store.current())
    }

    @Test
    fun keepsHostPortAndDropsThePath() = runTest {
        val built = buildStore(handler = ::ok)
        val choice = built.store.choose("https://example.com:8443/some/path") as ServerChoice.Ok
        assertEquals("https://example.com:8443", choice.baseUrl)
        assertNull(choice.inviteToken)
    }

    @Test
    fun refusesHttpWithoutAnyRequest() = runTest {
        val built = buildStore(handler = ::ok)
        assertEquals(ServerChoice.Failed(ServerChoiceFailure.NOT_HTTPS), built.store.choose("http://x"))
        assertEquals(emptyList<String>(), built.requested.toList())
    }

    @Test
    fun extractsTheInviteTokenFromAnInviteUrl() = runTest {
        val built = buildStore(handler = ::ok)
        val choice = built.store.choose("https://example.com/invite#$inviteToken") as ServerChoice.Ok
        assertEquals("https://example.com", choice.baseUrl)
        assertEquals(inviteToken, choice.inviteToken)
        assertEquals(listOf("https://example.com${Routes.config()}"), built.requested.toList())
    }

    @Test
    fun ignoresAFragmentOnAPathThatIsNotInvite() = runTest {
        val built = buildStore(handler = ::ok)
        val choice = built.store.choose("https://example.com/other#$inviteToken") as ServerChoice.Ok
        assertNull(choice.inviteToken)
    }

    @Test
    fun reportsNotKodakikoForAConfigWithTheWrongShapeOrANon2xxAnswer() = runTest {
        val notKodakiko = ServerChoice.Failed(ServerChoiceFailure.NOT_KODAKIKO)
        val wrongShape = buildStore { response(it.request(), 200, "{\"hello\":\"world\"}") }
        assertEquals(notKodakiko, wrongShape.store.choose("example.com"))
        val notFound = buildStore { response(it.request(), 404, "nope") }
        assertEquals(notKodakiko, notFound.store.choose("example.com"))
        val notJson = buildStore { response(it.request(), 200, "<html>") }
        assertEquals(notKodakiko, notJson.store.choose("example.com"))
        assertNull(wrongShape.stored())
    }

    @Test
    fun reportsUnreachableOnANetworkFailureAndOnTimeoutStoringNothing() = runTest {
        val unreachable = ServerChoice.Failed(ServerChoiceFailure.UNREACHABLE)
        val failing = buildStore { throw IOException("network down") }
        assertEquals(unreachable, failing.store.choose("example.com"))
        assertNull(failing.stored())

        val hanging = buildStore(timeoutMs = 10) { chain ->
            val deadline = System.nanoTime() + HANG_LIMIT_NANOS
            try {
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
            } catch (interrupted: InterruptedException) {
                throw InterruptedIOException("aborted")
            }
            throw IOException("aborted")
        }
        val started = System.nanoTime()
        assertEquals(unreachable, hanging.store.choose("example.com"))
        assertTrue(System.nanoTime() - started < TEST_BUDGET_NANOS)
        assertNull(hanging.stored())
    }

    @Test
    fun reportsInvalidForEmptyInputAndUnsupportedSchemes() = runTest {
        val built = buildStore(handler = ::ok)
        val invalid = ServerChoice.Failed(ServerChoiceFailure.INVALID)
        assertEquals(invalid, built.store.choose("   "))
        assertEquals(invalid, built.store.choose("ftp://example.com"))
        assertEquals(invalid, built.store.choose("https://"))
        assertEquals(emptyList<String>(), built.requested.toList())
    }

    @Test
    fun acceptsLocalHttpAddressesOnlyWhenAllowLocalHttpIsTrue() = runTest {
        val allowed = buildStore(allowLocalHttp = true, handler = ::ok)
        assertEquals("http://localhost:9580", (allowed.store.choose("http://localhost:9580") as ServerChoice.Ok).baseUrl)
        assertEquals("http://127.0.0.1:9580", (allowed.store.choose("http://127.0.0.1:9580") as ServerChoice.Ok).baseUrl)
        assertEquals("http://10.0.2.2:9580", (allowed.store.choose("http://10.0.2.2:9580") as ServerChoice.Ok).baseUrl)
        assertEquals(ServerChoice.Failed(ServerChoiceFailure.NOT_HTTPS), allowed.store.choose("http://example.com"))

        val refused = buildStore(allowLocalHttp = false, handler = ::ok)
        val notHttps = ServerChoice.Failed(ServerChoiceFailure.NOT_HTTPS)
        assertEquals(notHttps, refused.store.choose("http://localhost:9580"))
        assertEquals(notHttps, refused.store.choose("http://127.0.0.1:9580"))
        assertEquals(notHttps, refused.store.choose("http://10.0.2.2:9580"))
        assertEquals(emptyList<String>(), refused.requested.toList())
    }

    @Test
    fun returnsNullFromCurrentBeforeAnythingWasChosen() = runTest {
        val built = buildStore(handler = ::ok)
        assertNull(built.store.current())
    }

    @Test
    fun clearForgetsTheStoredServer() = runTest {
        val built = buildStore(handler = ::ok)
        built.store.choose("example.com")
        built.store.clear()
        assertNull(built.store.current())
        assertNull(built.stored())
    }

    private companion object {
        const val HANG_LIMIT_NANOS = 30_000_000_000L
        const val TEST_BUDGET_NANOS = 5_000_000_000L
        const val POLL_MS = 5L
    }
}
