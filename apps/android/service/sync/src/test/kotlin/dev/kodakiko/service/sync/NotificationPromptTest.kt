@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.sync

import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.service.account.DatabaseRowStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class NotificationPromptTest : SyncTestBase() {
    private lateinit var rowStore: DatabaseRowStore

    private fun promptTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            rowStore = DatabaseRowStore(context, sodium)
            rowStore.open("acct-" + randomIdOf(sodium)())
            body()
        }

    @After
    fun closeStore() {
        if (::rowStore.isInitialized) rowStore.close()
    }

    private fun TestScope.prompt(request: (suspend () -> Boolean)?): NotificationPrompt =
        NotificationPrompt(NotificationPromptDeps(rowStore = rowStore, request = request, ioDispatcher = StandardTestDispatcher(testScheduler)))

    @Test
    fun asksOnceShowsTheBannerWhileAskingAndStoresTheAnswer() = promptTest {
        val visibility = CopyOnWriteArrayList<Boolean>()
        val release = CompletableDeferred<Boolean>()
        val requests = AtomicInteger(0)
        val prompt = prompt {
            requests.incrementAndGet()
            release.await()
        }
        prompt.visible.subscribe { visibility += prompt.visible.value }
        val first = launch { prompt.ensureAsked() }
        runCurrent()
        assertTrue(prompt.visible.value)
        release.complete(false)
        first.join()
        assertFalse(prompt.visible.value)
        prompt.ensureAsked()
        assertEquals(1, requests.get())
        assertEquals(JsonPrimitive(false), rowStore.kvGetJson(StorageKeys.DEVICE_NOTIFICATION_PERMISSION_ASKED))
        assertEquals(listOf(true, false), visibility.toList())
    }

    @Test
    fun sharesOneRequestBetweenConcurrentCallers() = promptTest {
        val requests = AtomicInteger(0)
        val prompt = prompt {
            requests.incrementAndGet()
            true
        }
        joinAll(launch { prompt.ensureAsked() }, launch { prompt.ensureAsked() }, launch { prompt.ensureAsked() })
        assertEquals(1, requests.get())
    }

    @Test
    fun doesNothingWhenThePlatformHasNoPermissionRequest() = promptTest {
        val prompt = prompt(null)
        prompt.ensureAsked()
        assertFalse(prompt.visible.value)
        assertNull(rowStore.kvGet(StorageKeys.DEVICE_NOTIFICATION_PERMISSION_ASKED))
    }

    @Test
    fun hidesTheBannerAndAsksAgainLaterWhenTheRequestFails() = promptTest {
        val calls = AtomicInteger(0)
        val prompt = prompt {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("no activity")
            true
        }
        val failure = expectFailure<IllegalStateException> { prompt.ensureAsked() }
        assertEquals("no activity", failure.message)
        assertFalse(prompt.visible.value)
        prompt.ensureAsked()
        assertEquals(2, calls.get())
    }
}
