package dev.kodakiko.service.account

import dev.kodakiko.contract.Routes
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ServerUrlRedirectTest {
    private val first = MockWebServer()
    private val second = MockWebServer()

    @Before
    fun start() {
        first.start()
        second.start()
    }

    @After
    fun stop() {
        first.close()
        second.close()
    }

    @Test
    fun theConfigProbeDoesNotFollowARedirectToAnotherServer() = runTest {
        second.enqueue(MockResponse.Builder().code(OK).addHeader(CONTENT_TYPE, JSON).body(configJson(config())).build())
        first.enqueue(MockResponse.Builder().code(FOUND).addHeader(LOCATION, second.url(Routes.config()).toString()).build())
        val globalStore = MemoryGlobalStore()
        val store = HttpServerUrlStore(
            okHttpClient = OkHttpClient(),
            globalStore = globalStore,
            timeoutMs = TIMEOUT_MS,
            allowLocalHttp = true,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val choice = store.choose(first.url("/").toString().trimEnd('/'))

        assertEquals(ServerChoice.Failed(ServerChoiceFailure.NOT_KODAKIKO), choice)
        assertEquals(1, first.requestCount)
        assertEquals(0, second.requestCount)
        assertNull(globalStore.serverUrl())
    }

    private companion object {
        const val OK = 200
        const val FOUND = 302
        const val TIMEOUT_MS = 5_000L
        const val LOCATION = "Location"
        const val CONTENT_TYPE = "Content-Type"
        const val JSON = "application/json"
    }
}
