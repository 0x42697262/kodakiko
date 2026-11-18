package dev.kodakiko.ui.feature.auth

import dev.kodakiko.crypto.RandomSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoveryWordsLockTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()

    private val unlocked = MutableStateFlow(true)

    private var draw = 0

    private val random = RandomSource { byteArrayOf((draw++).toByte()) }

    private fun TestScope.open(): Pair<RecoveryKeyViewModel, Observed<RecoveryKeyState, AuthEvent>> {
        val vm = RecoveryKeyViewModel(auth = auth, random = random, unlocked = unlocked)
        return vm to observe(vm.state, vm.events)
    }

    @Test
    fun lockingDropsTheWordsAndTheTypedConfirmation() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.onTypedChange(0, RECOVERY_WORDS[0])
        assertEquals(RECOVERY_WORDS, screen.value.words)

        unlocked.value = false

        assertNull(screen.value.words)
        assertFalse(screen.value.confirming)
        assertTrue(screen.value.typed.none { it.isNotBlank() })
    }

    @Test
    fun unlockingAgainReloadsThePendingWords() = runTest {
        val (_, screen) = open()
        unlocked.value = false
        assertNull(screen.value.words)

        unlocked.value = true

        assertEquals(RECOVERY_WORDS, screen.value.words)
        assertEquals(2, auth.count("pendingRecoveryWords"))
    }
}
