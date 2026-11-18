package dev.kodakiko.ui.feature.auth

import dev.kodakiko.account.PasswordRule
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.text
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecoverViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val auth = FakeAuthService()
    private val accountState = Observable<AccountState>(AccountState.SignedOut())
    private val words = RECOVERY_WORDS.joinToString(" ")

    private fun TestScope.open(): Pair<RecoverViewModel, Observed<RecoverState, AuthEvent>> {
        val vm = RecoverViewModel(auth = auth, accountState = accountState)
        return vm to observe(vm.state, vm.events)
    }

    private fun RecoverViewModel.fill(username: String = "alice", newPassword: String = GOOD_PASSWORD, confirm: String = newPassword) {
        onUsernameChange(username)
        onWordsChange(words)
        onNewPasswordChange(newPassword)
        onConfirmChange(confirm)
    }

    @Test
    fun fromTheLockScreenTheLockedUsernameIsFilledIn() = runTest {
        accountState.value = AccountState.Locked
        auth.username = "alice"
        val (_, screen) = open()
        assertEquals("alice", screen.value.username)
        assertTrue(screen.value.showUsername)
    }

    @Test
    fun fromSignInTheUsernameStartsEmpty() = runTest {
        auth.username = "stale"
        val (_, screen) = open()
        assertEquals("", screen.value.username)
        assertEquals("", screen.value.words)
    }

    @Test
    fun aLocalJournalHidesTheUsername() = runTest {
        accountState.value = AccountState.Locked
        auth.mode.value = AccountMode.LOCAL
        val (_, screen) = open()
        assertFalse(screen.value.showUsername)
    }

    @Test
    fun recoversWithUsernameWordsAndNewPasswordThenGoesHome() = runTest {
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals(listOf<Any?>("alice", words, GOOD_PASSWORD), auth.args("recoverAccount"))
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }

    @Test
    fun hintsAppearOnceTheNewPasswordIsTyped() = runTest {
        val (vm, screen) = open()
        vm.onUsernameChange("alice")
        assertEquals(emptyList<PasswordRule>(), screen.value.hints)
        vm.onNewPasswordChange("short")
        assertEquals(listOf(PasswordRule.TOO_SHORT), screen.value.hints)
        assertTrue(screen.value.mismatch)
    }

    @Test
    fun aWeakOrMismatchedPasswordRecoversNothing() = runTest {
        val (vm, screen) = open()
        vm.fill(newPassword = "alice")
        vm.onSubmit()
        assertEquals(listOf(PasswordRule.TOO_SHORT, PasswordRule.EQUALS_USERNAME), screen.value.hints)
        vm.fill(confirm = OTHER_PASSWORD)
        vm.onSubmit()
        assertTrue(screen.value.mismatch)
        assertEquals(0, auth.count("recoverAccount"))
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun anUntouchedFormShowsTheHintsOnSubmit() = runTest {
        val (vm, screen) = open()
        vm.onSubmit()
        assertEquals(listOf(PasswordRule.TOO_SHORT, PasswordRule.EQUALS_USERNAME), screen.value.hints)
        assertEquals(0, auth.count("recoverAccount"))
    }

    @Test
    fun anUnknownWordNamesItsPositionAndBothPasswordsAreCleared() = runTest {
        auth.failures["recoverAccount"] = AuthError(AuthFailure.RECOVERY_WORDS_INVALID, position = 5)
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals(TestTags.Errors.RECOVERY_WORDS_INVALID, screen.value.error?.testTag)
        assertEquals("Word 5 isn't in the recovery word list.", screen.value.error!!.text(resources))
        assertEquals("", screen.value.newPassword)
        assertEquals("", screen.value.confirm)
        assertEquals(words, screen.value.words)
        assertEquals("alice", screen.value.username)
        assertTrue(screen.events.isEmpty())
    }

    @Test
    fun wrongWordsShowTheGeneralWordsMessage() = runTest {
        auth.failures["recoverAccount"] = AuthError(AuthFailure.RECOVERY_WORDS_INVALID)
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertEquals("These words don't form a valid recovery key.", screen.value.error!!.text(resources))
    }

    @Test
    fun throttlingAndTransportFailuresShowTheirMessages() = runTest {
        for ((reason, tag) in mapOf(AuthFailure.RATE_LIMITED to TestTags.Errors.RATE_LIMITED, AuthFailure.UNREACHABLE to TestTags.Errors.UNREACHABLE)) {
            auth.failures["recoverAccount"] = AuthError(reason)
            val (vm, screen) = open()
            vm.fill()
            vm.onSubmit()
            assertEquals("$reason", tag, screen.value.error?.testTag)
        }
    }

    @Test
    fun isBusyWhileRecoveringAndRecoversOnce() = runTest {
        val gate = auth.hold("recoverAccount")
        val (vm, screen) = open()
        vm.fill()
        vm.onSubmit()
        assertTrue(screen.value.busy)
        vm.onSubmit()
        assertEquals(1, auth.count("recoverAccount"))
        gate.complete(Unit)
        assertFalse(screen.value.busy)
        assertEquals(listOf(AuthEvent.ReplaceAll(Route.Home)), screen.events)
    }
}
