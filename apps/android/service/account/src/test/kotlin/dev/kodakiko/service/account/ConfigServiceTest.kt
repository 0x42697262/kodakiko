@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.account

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.data.net.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import java.util.Collections

class ConfigServiceTest {
    private val config: PublicConfigDto = config(
        signupMode = SignupMode.OPEN,
        sharingEnabled = true,
        publicLinksEnabled = false,
        maxMediaPerEntry = 10,
        maxUploadBytes = 1000,
        defaultQuotaBytes = 0,
        trashDays = 14,
        accountDeletionGraceDays = 7,
        serverVersion = "0.1.0",
    )
    private val server = MockWebServer()

    @Volatile
    private var online = true

    private val configRequests = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                configRequests.add(request.url.encodedPath)
                if (!online) return disconnectResponse()
                if (request.url.encodedPath != Routes.config()) return MockResponse.Builder().code(404).build()
                return jsonResponse(200, ContractSchemas.publicConfigSchema.encode(config))
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun http() = HttpClient(
        baseUrl = { server.url("/").toString().trimEnd('/') },
        sessionToken = { null },
        onSessionInvalid = {},
        sleep = {},
        okHttpClient = sharedOkHttp,
    )

    private fun TestScope.service(globalStore: MemoryGlobalStore, lifecycle: FakeLifecycle): ConfigService =
        DefaultConfigService(
            http = http(),
            globalStore = globalStore,
            lifecycle = lifecycle,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun assertStored(globalStore: MemoryGlobalStore, expected: PublicConfigDto) {
        val stored = globalStore.serverConfigJson() ?: throw AssertionError("expected a stored config")
        assertEquals(Json.parseToJsonElement(configJson(expected)), Json.parseToJsonElement(stored))
    }

    @Test
    fun loadsTheStoredValueAtStartRefreshesNotifiesAndKeepsTheCacheOnFailure() = runTest {
        val globalStore = MemoryGlobalStore()
        globalStore.setServerConfigJson(configJson(config.copy(trashDays = 30)))
        val lifecycle = FakeLifecycle()
        val service = service(globalStore, lifecycle)
        assertEquals(30L, service.current()?.trashDays)

        val heard = Collections.synchronizedList(mutableListOf<PublicConfigDto>())
        service.subscribe { heard.add(it) }
        service.refresh()
        assertEquals(14L, service.current()?.trashDays)
        assertTrue(heard.contains(config))
        assertStored(globalStore, config)

        online = false
        assertEquals(config, service.refresh())
    }

    @Test
    fun refreshesEveryTimeTheAppBecomesActiveAndIgnoresACorruptStoredValue() = runTest {
        val globalStore = MemoryGlobalStore()
        globalStore.setServerConfigJson("{broken")
        val lifecycle = FakeLifecycle()
        val service = service(globalStore, lifecycle)
        assertNull(service.current())
        runCurrent()
        val initialCalls = configRequests.size
        assertEquals(1, initialCalls)
        lifecycle.activeListeners[0](true)
        lifecycle.activeListeners[0](false)
        runCurrent()
        assertEquals(initialCalls + 1, configRequests.size)
    }

    @Test
    fun acceptsAConfigHandedOverByTheServerChooserCurrentStoredAndAnnounced() = runTest {
        online = false
        val globalStore = MemoryGlobalStore()
        val service = service(globalStore, FakeLifecycle())
        val heard = Collections.synchronizedList(mutableListOf<PublicConfigDto>())
        service.subscribe { heard.add(it) }

        service.accept(config)

        assertEquals(config, service.current())
        assertStored(globalStore, config)
        assertTrue(heard.contains(config))
    }

    @Test
    fun stopsAnnouncingToAnUnsubscribedListener() = runTest {
        online = false
        val service = service(MemoryGlobalStore(), FakeLifecycle())
        val heard = Collections.synchronizedList(mutableListOf<PublicConfigDto>())
        val unsubscribe: () -> Unit = service.subscribe { heard.add(it) }
        unsubscribe()
        service.accept(config)
        assertEquals(emptyList<PublicConfigDto>(), heard.toList())
    }
}
