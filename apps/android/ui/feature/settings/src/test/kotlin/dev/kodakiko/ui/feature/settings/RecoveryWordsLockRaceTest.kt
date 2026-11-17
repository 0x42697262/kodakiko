@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoveryWordsLockRaceTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    @Test
    fun wordsRevealedAfterTheJournalLockedNeverReachTheState() = runTest {
        val vm = AccountSettingsViewModel(h.accountDeps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        h.credentials.revealGate = gate
        vm.onAction(AccountSettingsAction.ShowRecovery)
        vm.onAction(AccountSettingsAction.SetRecoveryPassword(GOOD_PASSWORD))
        vm.onAction(AccountSettingsAction.RevealRecovery)
        runCurrent()
        assertEquals(listOf(GOOD_PASSWORD), h.credentials.passwords)

        h.profile.unlockedState.value = false
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertFalse("recovery words shown while locked: ${vm.state.value.recovery}", vm.state.value.recovery is RecoverySheet.Words)
    }
}
