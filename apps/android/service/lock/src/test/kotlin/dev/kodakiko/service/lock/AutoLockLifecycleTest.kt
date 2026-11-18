@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.lock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.kodakiko.platform.device.ForegroundTracker
import dev.kodakiko.service.account.ForegroundLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoLockLifecycleTest {
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun backgroundAutoLockFollowsTheProcessLifecycleThroughTheForegroundTracker() = runTest {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        var harness: AutoLockHarness? = null
        val tracker = ForegroundTracker(owner.lifecycle) { harness?.clock?.now() ?: START }
        harness = AutoLockHarness(this, lifecycle = ForegroundLifecycle(tracker))

        owner.registry.currentState = Lifecycle.State.CREATED
        harness.setSystemTime(START + 5 * MINUTE - SECOND)
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(emptyList<LockReason>(), harness.locks)

        owner.registry.currentState = Lifecycle.State.CREATED
        harness.setSystemTime(START + 10 * MINUTE)
        owner.registry.currentState = Lifecycle.State.STARTED
        assertEquals(listOf(LockReason.BACKGROUND), harness.locks)

        harness.accountState.value = unlockedState()
        owner.registry.currentState = Lifecycle.State.CREATED
        harness.advanceTimersBy(5 * MINUTE)
        assertEquals(listOf(LockReason.BACKGROUND, LockReason.BACKGROUND), harness.locks)
    }
}
