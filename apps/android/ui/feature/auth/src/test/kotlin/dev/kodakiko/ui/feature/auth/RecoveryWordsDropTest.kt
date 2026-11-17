package dev.kodakiko.ui.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.kodakiko.crypto.RandomSource
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoveryWordsDropTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()

    private var draw = 0

    private val random = RandomSource { byteArrayOf((draw++).toByte()) }

    private fun TestScope.open(): Pair<RecoveryKeyViewModel, Observed<RecoveryKeyState, AuthEvent>> {
        val vm = RecoveryKeyViewModel(auth = auth, random = random)
        return vm to observe(vm.state, vm.events)
    }

    private fun RecoveryKeyViewModel.confirmEveryAskedWord() {
        onWrittenDown()
        state.value.positions.forEachIndexed { index, position -> onTypedChange(index, RECOVERY_WORDS[position - 1]) }
        onConfirm()
    }

    private fun RecoveryKeyState.holdsNoWord(): Boolean = words == null && typed.none { it.isNotBlank() }

    @Test
    fun confirmingTheWordsDropsThemFromTheState() = runTest {
        val (vm, screen) = open()

        vm.confirmEveryAskedWord()

        assertEquals(1, auth.count("confirmRecoveryKeySaved"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
        assertNull(screen.value.words)
        assertTrue("state still holds recovery words: ${screen.value.typed}", screen.value.holdsNoWord())
    }

    @Test
    fun clearingTheViewModelDropsTheWords() = runTest {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RecoveryKeyViewModel(auth = auth, random = random) as T
        }
        val vm = ViewModelProvider(store, factory)[RecoveryKeyViewModel::class.java]
        val screen = observe(vm.state, vm.events)
        vm.onWrittenDown()
        vm.onTypedChange(0, RECOVERY_WORDS[0])
        assertEquals(RECOVERY_WORDS, screen.value.words)

        store.clear()

        assertTrue("state still holds recovery words after onCleared", vm.state.value.holdsNoWord())
    }
}
