package dev.kodakiko.ui.feature.auth

import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.ui.core.navigation.Route
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
class RecoveryKeyViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()

    private val draws = mutableListOf<Int>()

    private val random = RandomSource { length ->
        draws += length
        byteArrayOf(listOf(4, 250, 9, 4, 22)[(draws.size - 1).coerceAtMost(4)].toByte())
    }

    private val positions = listOf(5, 10, 23)

    private fun TestScope.open(): Pair<RecoveryKeyViewModel, Observed<RecoveryKeyState, AuthEvent>> {
        val vm = RecoveryKeyViewModel(auth = auth, random = random)
        return vm to observe(vm.state, vm.events)
    }

    private fun RecoveryKeyViewModel.typeWords(words: List<String>) {
        words.forEachIndexed { index, word -> onTypedChange(index, word) }
    }

    @Test
    fun loadsThePendingWordsAndShowsThemFirst() = runTest {
        val (_, screen) = open()
        assertEquals(RECOVERY_WORDS, screen.value.words)
        assertFalse(screen.value.confirming)
        assertEquals(1, auth.count("pendingRecoveryWords"))
    }

    @Test
    fun withoutPendingWordsItLeavesForHome() = runTest {
        auth.pendingWords = null
        val (_, screen) = open()
        assertNull(screen.value.words)
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }

    @Test
    fun aFailedLoadLeavesForHome() = runTest {
        auth.failures["pendingRecoveryWords"] = IllegalStateException("locked")
        val (_, screen) = open()
        assertNull(screen.value.words)
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }

    @Test
    fun writtenDownAsksForThreeRandomPositionsWithEmptyFields() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        assertTrue(screen.value.confirming)
        assertEquals(positions, screen.value.positions)
        assertEquals(listOf("", "", ""), screen.value.typed)
        assertFalse(screen.value.mismatch)
        assertEquals(listOf(1, 1, 1, 1, 1), draws)
    }

    @Test
    fun typingFillsOnlyItsOwnField() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.onTypedChange(1, "access")
        assertEquals(listOf("", "access", ""), screen.value.typed)
    }

    @Test
    fun theRightWordsConfirmTheKeyAndGoHome() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.typeWords(positions.map { RECOVERY_WORDS[it - 1] })
        vm.onConfirm()
        assertEquals(1, auth.count("confirmRecoveryKeySaved"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
        assertFalse(screen.value.mismatch)
    }

    @Test
    fun wordsAreComparedTrimmedAndInLowerCase() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.typeWords(positions.map { "  " + RECOVERY_WORDS[it - 1].uppercase() + " " })
        vm.onConfirm()
        assertEquals(1, auth.count("confirmRecoveryKeySaved"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }

    @Test
    fun oneWrongWordShowsTheMismatchAndConfirmsNothing() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.typeWords(listOf(RECOVERY_WORDS[positions[0] - 1], RECOVERY_WORDS[positions[1] - 1], "actor"))
        vm.onConfirm()
        assertTrue(screen.value.mismatch)
        assertEquals(0, auth.count("confirmRecoveryKeySaved"))
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun anEmptyFieldIsAMismatch() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.onConfirm()
        assertTrue(screen.value.mismatch)
        assertEquals(0, auth.count("confirmRecoveryKeySaved"))
    }

    @Test
    fun aCorrectRetryClearsTheMismatch() = runTest {
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.onConfirm()
        assertTrue(screen.value.mismatch)
        vm.typeWords(positions.map { RECOVERY_WORDS[it - 1] })
        vm.onConfirm()
        assertFalse(screen.value.mismatch)
        assertEquals(1, auth.count("confirmRecoveryKeySaved"))
    }

    @Test
    fun isBusyWhileConfirmingAndConfirmsOnce() = runTest {
        val gate = auth.hold("confirmRecoveryKeySaved")
        val (vm, screen) = open()
        vm.onWrittenDown()
        vm.typeWords(positions.map { RECOVERY_WORDS[it - 1] })
        vm.onConfirm()
        assertTrue(screen.value.busy)
        vm.onConfirm()
        assertEquals(1, auth.count("confirmRecoveryKeySaved"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }
}
