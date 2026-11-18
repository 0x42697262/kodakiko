package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchema
import dev.kodakiko.contract.ContractViolation
import dev.kodakiko.contract.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HttpClientTest {
    private val server = MockWebServer()
    private val sessionToken = "kdk_s_test-token"
    private val sleeps = mutableListOf<Long>()
    private var sessionInvalid = 0

    private object OkSchema : ContractSchema<Boolean> {
        override val name = "test.ok"

        override fun decode(json: JsonElement): Boolean {
            val ok = (json as? JsonObject)?.get("ok") as? JsonPrimitive
            if (ok == null || ok.isString || ok.content !in setOf("true", "false")) throw ContractViolation(name, "ok")
            return ok.boolean
        }

        override fun encode(value: Boolean): JsonElement = buildJsonObject { put("ok", value) }
    }

    private fun client(random: Double = 0.5) = HttpClient(
        baseUrl = { server.url("").toString().trimEnd('/') },
        sessionToken = { sessionToken },
        onSessionInvalid = { sessionInvalid++ },
        sleep = { sleeps += it },
        random = { random },
    )

    private val getCall = ApiCall("GET", "/api/v1/config", null, OkSchema, ApiAuth.Session, idempotent = true)
    private val postCall = ApiCall("POST", "/api/v1/entries", buildJsonObject { put("a", 1) }, OkSchema, ApiAuth.Session, idempotent = false)

    private fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        MockResponse.Builder().code(status).headers(
            Headers.Builder().apply {
                add("content-type", "application/json")
                headers.forEach { (k, v) -> add(k, v) }
            }.build(),
        ).body(body).build()

    private fun errorBody(code: String, requestId: String = "req-1", details: String = "null") =
        "{\"error\":{\"code\":\"$code\",\"message\":\"message\",\"requestId\":\"$requestId\",\"details\":$details}}"

    private fun disconnect() = MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    @Test
    fun sendsMethodUrlBearerHeaderAndJsonBody() {
        server.enqueue(json(200, "{\"ok\":true}"))
        assertEquals(true, client().request(postCall))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/entries", recorded.url.encodedPath)
        assertEquals("Bearer $sessionToken", recorded.headers["Authorization"])
        assertEquals("{\"a\":1}", recorded.body!!.utf8())
        assertEquals("application/json", recorded.headers["Content-Type"]!!.substringBefore(';'))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sendsNoAuthorizationForAuthNoneAndTheLinkTokenForLinkAuth() {
        server.enqueue(json(200, "{\"ok\":true}"))
        server.enqueue(json(200, "{\"ok\":true}"))
        server.enqueue(json(200, "{\"ok\":true}"))
        client().request(getCall.copy(auth = ApiAuth.None))
        client().request(getCall.copy(auth = ApiAuth.Link("link-token", null)))
        client().request(getCall.copy(auth = ApiAuth.Link("link-token", "view-token")))
        val none = server.takeRequest()
        val link = server.takeRequest()
        val view = server.takeRequest()
        assertNull(none.headers["Authorization"])
        assertNull(none.headers["Content-Type"])
        assertNull(link.headers["Authorization"])
        assertEquals("link-token", link.headers["X-Kodakiko-Link-Token"])
        assertNull(link.headers["X-Kodakiko-View-Token"])
        assertEquals("view-token", view.headers["X-Kodakiko-View-Token"])
    }

    @Test
    fun aRequestThatExceedsItsTimeoutIsANetworkError() {
        server.enqueue(MockResponse.Builder().code(200).body("{\"ok\":true}").headersDelay(2, java.util.concurrent.TimeUnit.SECONDS).build())
        assertThrows(NetworkError::class.java) { client().request(postCall.copy(timeoutMs = 100)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesAnIdempotentGetOn503ThenSucceeds() {
        server.enqueue(json(503, errorBody("STORAGE_UNAVAILABLE")))
        server.enqueue(json(200, "{\"ok\":true}"))
        assertEquals(true, client().request(getCall))
        assertEquals(2, server.requestCount)
        assertEquals(listOf(250L), sleeps)
    }

    @Test
    fun retriesAFailingIdempotentGetThreeTimesWithFullJitterAndReportsTheLastError() {
        repeat(3) { server.enqueue(json(503, errorBody("STORAGE_UNAVAILABLE"))) }
        val error = assertThrows(ApiError::class.java) { client().request(getCall) }
        assertEquals(503, error.status)
        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, error.code)
        assertEquals(HttpConstants.RETRY_ATTEMPTS, server.requestCount)
        assertEquals(listOf(250L, 500L), sleeps)
    }

    @Test
    fun retriesAnIdempotentGetAfterNetworkFailuresAndThenReportsNetworkError() {
        repeat(3) { server.enqueue(disconnect()) }
        assertThrows(NetworkError::class.java) { client().request(getCall) }
        assertEquals(listOf(250L, 500L), sleeps)
    }

    @Test
    fun recoversWhenANetworkFailureIsFollowedByASuccess() {
        server.enqueue(disconnect())
        server.enqueue(json(200, "{\"ok\":true}"))
        assertEquals(true, client().request(getCall))
    }

    @Test
    fun doesNotRetryAPostOn503() {
        server.enqueue(json(503, errorBody("STORAGE_UNAVAILABLE")))
        assertEquals(503, assertThrows(ApiError::class.java) { client().request(postCall) }.status)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun doesNotRetryIdempotentCallsOnOtherStatuses() {
        server.enqueue(json(404, errorBody("NOT_FOUND")))
        val error = assertThrows(ApiError::class.java) { client().request(getCall) }
        assertEquals(404, error.status)
        assertEquals(ErrorCode.NOT_FOUND, error.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun waitsRetryAfterSecondsOn429AndCapsHugeValues() {
        server.enqueue(json(429, errorBody("RATE_LIMITED"), mapOf("retry-after" to "2")))
        server.enqueue(json(200, "{\"ok\":true}"))
        server.enqueue(json(429, errorBody("RATE_LIMITED"), mapOf("retry-after" to "3600")))
        server.enqueue(json(200, "{\"ok\":true}"))
        client().request(getCall)
        client().request(getCall)
        assertEquals(listOf(2000L, 60_000L), sleeps)
        assertEquals(60_000L, HttpConstants.RETRY_AFTER_MAX_MS)
    }

    @Test
    fun anErrorBodyBecomesAnApiErrorWithStatusCodeDetailsAndRequestId() {
        server.enqueue(json(409, errorBody("REVISION_CONFLICT", "req-9", "{\"revision\":3}")))
        val error = assertThrows(ApiError::class.java) { client().request(postCall) }
        assertEquals(409, error.status)
        assertEquals(ErrorCode.REVISION_CONFLICT, error.code)
        assertEquals(Json.parseToJsonElement("{\"revision\":3}"), error.details)
        assertEquals("req-9", error.requestId)
    }

    @Test
    fun aNonJsonErrorIsInternalWithTheRequestIdHeader() {
        server.enqueue(MockResponse.Builder().code(400).addHeader("X-Request-Id", "req-h").body("gateway oops").build())
        val error = assertThrows(ApiError::class.java) { client().request(postCall) }
        assertEquals(400, error.status)
        assertEquals(ErrorCode.INTERNAL, error.code)
        assertEquals("req-h", error.requestId)
    }

    @Test
    fun onlySessionExpiredAndUnauthenticatedSignalAnInvalidSession() {
        server.enqueue(json(401, errorBody("SESSION_EXPIRED")))
        server.enqueue(json(401, errorBody("UNAUTHENTICATED")))
        server.enqueue(json(401, errorBody("INVALID_CREDENTIALS")))
        assertEquals(ErrorCode.SESSION_EXPIRED, assertThrows(ApiError::class.java) { client().request(getCall) }.code)
        assertEquals(1, sessionInvalid)
        assertThrows(ApiError::class.java) { client().request(getCall) }
        assertEquals(2, sessionInvalid)
        assertThrows(ApiError::class.java) { client().request(postCall) }
        assertEquals(2, sessionInvalid)
    }

    @Test
    fun aBodyThatDoesNotMatchTheSchemaIsInternal() {
        server.enqueue(json(200, "{\"ok\":\"nope\"}"))
        val error = assertThrows(ApiError::class.java) { client().request(postCall) }
        assertEquals(200, error.status)
        assertEquals(ErrorCode.INTERNAL, error.code)
    }

    @Test
    fun anEmpty204IsAcceptedForTheVoidSchema() {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(Unit, client().request(ApiCall("POST", "/api/v1/x", null, VoidResponse, ApiAuth.Session, idempotent = false)))
    }

    @Test
    fun retryAfterSecondsIsCarriedOrNull() {
        server.enqueue(json(429, errorBody("RATE_LIMITED"), mapOf("Retry-After" to "17")))
        server.enqueue(json(429, errorBody("RATE_LIMITED")))
        server.enqueue(json(429, errorBody("RATE_LIMITED"), mapOf("Retry-After" to "soon")))
        server.enqueue(json(429, errorBody("RATE_LIMITED"), mapOf("Retry-After" to " ")))
        val call = postCall.copy(auth = ApiAuth.None)
        assertEquals(17.0, assertThrows(ApiError::class.java) { client().request(call) }.retryAfterSeconds)
        assertNull(assertThrows(ApiError::class.java) { client().request(call) }.retryAfterSeconds)
        assertNull(assertThrows(ApiError::class.java) { client().request(call) }.retryAfterSeconds)
        assertNull(assertThrows(ApiError::class.java) { client().request(call) }.retryAfterSeconds)
    }

    @Test
    fun theBackoffCeilingIsCappedAndRandomIsFloored() {
        assertEquals(7992L, backoffDelay(10, 0.999))
        assertEquals(0L, backoffDelay(0, 0.0))
        assertEquals(499L, backoffDelay(0, 0.999))
    }
}
