package dev.kodakiko.ui.feature.lock

import android.os.Looper
import dev.kodakiko.data.secure.DeviceGate
import dev.kodakiko.data.secure.DeviceGateReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
class BiometricDeviceGateTest {
    private class RecordingLauncher : PromptLauncher {
        val requests = CopyOnWriteArrayList<PromptRequest>()
        val results = CopyOnWriteArrayList<(PromptResult) -> Unit>()
        val threads = CopyOnWriteArrayList<Thread>()

        override fun launch(request: PromptRequest, onResult: (PromptResult) -> Unit) {
            requests += request
            results += onResult
            threads += Thread.currentThread()
        }
    }

    private val launcher = RecordingLauncher()
    private val gate: DeviceGate = BiometricDeviceGate(launcher)
    private val workers = Executors.newFixedThreadPool(2)

    @After
    fun stopWorkers() {
        workers.shutdownNow()
    }

    private fun <T> onWorker(block: () -> T): Future<T> = workers.submit(Callable { block() })

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(PUMP_MS)
        }
    }

    private fun pumpFor(times: Int) {
        repeat(times) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(PUMP_MS)
        }
    }

    private fun <T> Future<T>.await(): T {
        pumpUntil { isDone }
        return get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun cipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    @Test
    fun aWorkerThreadWaitsForThePromptShownOnTheMainThread() {
        val answer = onWorker { gate.authenticate(DeviceGateReason.UNLOCK) }
        pumpUntil { launcher.requests.size == 1 }
        assertFalse(answer.isDone)
        assertSame(Looper.getMainLooper().thread, launcher.threads.single())
        assertEquals(PromptRequest(DeviceGateReason.UNLOCK, cipher = null), launcher.requests.single())
        launcher.results.single()(PromptResult.Succeeded(null))
        assertTrue(answer.await())
    }

    @Test
    fun aCancelledOrFailedPromptIsARefusal() {
        val answer = onWorker { gate.authenticate(DeviceGateReason.LEGACY_UNLOCK) }
        pumpUntil { launcher.requests.size == 1 }
        assertEquals(DeviceGateReason.LEGACY_UNLOCK, launcher.requests.single().reason)
        launcher.results.single()(PromptResult.Failed)
        assertFalse(answer.await())
    }

    @Test
    fun aCipherIsHandedToThePromptAndTheAuthenticatedCipherComesBack() {
        val locked = cipher()
        val unlocked = cipher()
        val answer = onWorker { gate.authenticateCipher(DeviceGateReason.STORE_MASTER_KEY, locked) }
        pumpUntil { launcher.requests.size == 1 }
        val request = launcher.requests.single()
        assertEquals(DeviceGateReason.STORE_MASTER_KEY, request.reason)
        assertSame(locked, request.cipher)
        launcher.results.single()(PromptResult.Succeeded(unlocked))
        assertSame(unlocked, answer.await())
    }

    @Test
    fun aFailedCipherPromptGivesNoCipher() {
        val answer = onWorker { gate.authenticateCipher(DeviceGateReason.UNLOCK, cipher()) }
        pumpUntil { launcher.requests.size == 1 }
        launcher.results.single()(PromptResult.Failed)
        assertNull(answer.await())
    }

    @Test
    fun aSuccessWithoutTheCipherIsNotAnAuthenticatedCipher() {
        val answer = onWorker { gate.authenticateCipher(DeviceGateReason.UNLOCK, cipher()) }
        pumpUntil { launcher.requests.size == 1 }
        launcher.results.single()(PromptResult.Succeeded(null))
        assertNull(answer.await())
    }

    @Test
    fun theMainThreadIsRefusedAtOnceInsteadOfDeadlocking() {
        assertFalse(gate.authenticate(DeviceGateReason.UNLOCK))
        assertNull(gate.authenticateCipher(DeviceGateReason.UNLOCK, cipher()))
        pumpFor(PUMP_ROUNDS)
        assertTrue(launcher.requests.isEmpty())
    }

    @Test
    fun onlyOnePromptIsShownAtATime() {
        val first = onWorker { gate.authenticate(DeviceGateReason.UNLOCK) }
        pumpUntil { launcher.requests.size == 1 }
        val second = onWorker { gate.authenticate(DeviceGateReason.STORE_MASTER_KEY) }
        pumpFor(PUMP_ROUNDS)
        assertEquals(1, launcher.requests.size)
        assertFalse(second.isDone)
        launcher.results[0](PromptResult.Succeeded(null))
        assertTrue(first.await())
        pumpUntil { launcher.requests.size == 2 }
        assertEquals(DeviceGateReason.STORE_MASTER_KEY, launcher.requests[1].reason)
        launcher.results[1](PromptResult.Failed)
        assertFalse(second.await())
    }

    @Test
    fun aSecondResultForTheSamePromptIsIgnored() {
        val answer = onWorker { gate.authenticate(DeviceGateReason.UNLOCK) }
        pumpUntil { launcher.requests.size == 1 }
        launcher.results.single()(PromptResult.Succeeded(null))
        launcher.results.single()(PromptResult.Failed)
        assertTrue(answer.await())
        val next = onWorker { gate.authenticate(DeviceGateReason.UNLOCK) }
        pumpUntil { launcher.requests.size == 2 }
        launcher.results[1](PromptResult.Failed)
        assertFalse(next.await())
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val PUMP_MS = 5L
        const val PUMP_ROUNDS = 20
    }
}
