@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.account.checkPasswordPolicy
import dev.kodakiko.account.normalizeUsername
import dev.kodakiko.data.net.NetworkError
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class DeletionAllowedTest {
    @Test
    fun needsTheExactUsernameAndAPassword() {
        assertTrue(deletionAllowed("ada", "ada", "pw"))
        assertFalse(deletionAllowed("Ada", "ada", "pw"))
        assertFalse(deletionAllowed("ad", "ada", "pw"))
        assertFalse(deletionAllowed("ada", "ada", ""))
        assertTrue(deletionAllowed(" ada ", "ada", "pw"))
    }
}

@RunWith(RobolectricTestRunner::class)
class PasswordViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: PasswordViewModel, val events: MutableList<SettingsEvent>) {
        val state: PasswordState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = PasswordViewModel(h.accountDeps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun Opened.fill(current: String, next: String, confirm: String) {
        vm.onAction(PasswordAction.SetCurrent(current))
        vm.onAction(PasswordAction.SetNext(next))
        vm.onAction(PasswordAction.SetConfirm(confirm))
    }

    @Test
    fun theFormStartsEmptyWithoutHintsAndCannotBeSubmitted() = runTest {
        val o = open()
        assertEquals(PasswordState(current = "", next = "", confirm = "", busy = false, message = null, hints = emptyList()), o.state)
        assertFalse(o.state.mismatch)
        assertFalse(o.state.canSubmit)
    }

    @Test
    fun theHintsAreThePasswordPolicyAgainstTheNormalizedUsernameOnceTheNewPasswordIsTyped() = runTest {
        h.profile.usernameState.value = "Ada"
        val o = open()
        o.vm.onAction(PasswordAction.SetNext("ada"))
        runCurrent()
        assertEquals(checkPasswordPolicy("ada", normalizeUsername("Ada")), o.state.hints)
        assertTrue(o.state.hints.contains(PasswordRule.EQUALS_USERNAME))
        assertTrue(o.state.hints.contains(PasswordRule.TOO_SHORT))
        o.vm.onAction(PasswordAction.SetNext(NEW_PASSWORD))
        runCurrent()
        assertEquals(emptyList<PasswordRule>(), o.state.hints)
        o.vm.onAction(PasswordAction.SetNext(""))
        runCurrent()
        assertEquals(emptyList<PasswordRule>(), o.state.hints)
    }

    @Test
    fun aMismatchShowsOnlyOnceTheConfirmationIsTyped() = runTest {
        val o = open()
        o.fill(GOOD_PASSWORD, NEW_PASSWORD, "")
        runCurrent()
        assertFalse(o.state.mismatch)
        assertFalse(o.state.canSubmit)
        o.vm.onAction(PasswordAction.SetConfirm("a brand new"))
        runCurrent()
        assertTrue(o.state.mismatch)
        assertFalse(o.state.canSubmit)
        o.vm.onAction(PasswordAction.SetConfirm(NEW_PASSWORD))
        runCurrent()
        assertFalse(o.state.mismatch)
        assertTrue(o.state.canSubmit)
    }

    @Test
    fun theCurrentPasswordAndAPolicyConformingNewOneAreRequired() = runTest {
        val o = open()
        o.fill("", NEW_PASSWORD, NEW_PASSWORD)
        runCurrent()
        assertFalse(o.state.canSubmit)
        o.fill(GOOD_PASSWORD, "short", "short")
        runCurrent()
        assertFalse(o.state.canSubmit)
        o.vm.onAction(PasswordAction.Submit)
        runCurrent()
        assertEquals(emptyList<String>(), h.credentials.calls)
    }

    @Test
    fun aSuccessfulChangeClearsTheFieldsAndSaysSo() = runTest {
        val o = open()
        o.fill(GOOD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
        o.vm.onAction(PasswordAction.Submit)
        runCurrent()
        assertEquals(listOf("${FakeSettingsCredentials.CHANGE_PASSWORD}:$GOOD_PASSWORD:$NEW_PASSWORD"), h.credentials.calls)
        assertEquals(PasswordState(current = "", next = "", confirm = "", busy = false, message = PasswordMessage.CHANGED, hints = emptyList()), o.state)
    }

    @Test
    fun whileTheNewKeyIsDerivedTheFormIsBusyAndTheOldMessageIsGone() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.credentials.gate = gate
        val o = open()
        o.fill(GOOD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
        o.vm.onAction(PasswordAction.Submit)
        runCurrent()
        assertTrue(o.state.busy)
        assertNull(o.state.message)
        o.vm.onAction(PasswordAction.Submit)
        runCurrent()
        assertEquals(1, h.credentials.calls.size)
        gate.complete(Unit)
        runCurrent()
        assertFalse(o.state.busy)
        assertEquals(PasswordMessage.CHANGED, o.state.message)
    }

    @Test
    fun aWrongCurrentPasswordKeepsTheFieldsAndSaysItIsIncorrect() = runTest {
        for (reason in listOf(AuthFailure.WRONG_PASSWORD, AuthFailure.INVALID_CREDENTIALS)) {
            h.credentials.changeFailure = AuthError(reason)
            val o = open()
            o.fill("not it", NEW_PASSWORD, NEW_PASSWORD)
            o.vm.onAction(PasswordAction.Submit)
            runCurrent()
            assertEquals(reason.name, PasswordMessage.WRONG_PASSWORD, o.state.message)
            assertEquals("not it", o.state.current)
            assertEquals(NEW_PASSWORD, o.state.next)
            assertFalse(o.state.busy)
        }
    }

    @Test
    fun anyOtherFailureSaysItCouldNotSave() = runTest {
        for (failure in listOf(AuthError(AuthFailure.UNREACHABLE), NetworkError(), IllegalStateException("boom"))) {
            h.credentials.changeFailure = failure
            val o = open()
            o.fill(GOOD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
            o.vm.onAction(PasswordAction.Submit)
            runCurrent()
            assertEquals(failure.toString(), PasswordMessage.FAILED, o.state.message)
        }
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(PasswordAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
class DeleteAccountViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: DeleteAccountViewModel, val events: MutableList<SettingsEvent>) {
        val state: DeleteAccountState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = DeleteAccountViewModel(h.accountDeps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    private fun Opened.confirmWith(typed: String, password: String) {
        vm.onAction(DeleteAccountAction.SetTyped(typed))
        vm.onAction(DeleteAccountAction.SetPassword(password))
        vm.onAction(DeleteAccountAction.Confirm)
    }

    @Test
    fun theFormStartsEmptyWithTheServerGracePeriod() = runTest {
        val o = open()
        assertEquals(
            DeleteAccountState(local = false, graceDays = 7, username = USERNAME, typed = "", password = "", busy = false, error = null),
            o.state,
        )
        assertFalse(o.state.canConfirm)
        h.config.config = publicConfig().copy(accountDeletionGraceDays = 14)
        assertEquals(14L, open().state.graceDays)
        h.config.config = null
        assertEquals(7L, open().state.graceDays)
    }

    @Test
    fun confirmingNeedsTheUsernameAndThePassword() = runTest {
        val o = open()
        o.vm.onAction(DeleteAccountAction.SetTyped("Ada"))
        o.vm.onAction(DeleteAccountAction.SetPassword(GOOD_PASSWORD))
        runCurrent()
        assertFalse(o.state.canConfirm)
        o.vm.onAction(DeleteAccountAction.Confirm)
        runCurrent()
        assertEquals(emptyList<String>(), h.credentials.calls)
        o.vm.onAction(DeleteAccountAction.SetTyped(" ada "))
        runCurrent()
        assertTrue(o.state.canConfirm)
    }

    @Test
    fun aServerAccountChecksThePasswordThenSchedulesDeletionAndGoesToSignIn() = runTest {
        val o = open()
        o.confirmWith(" ada ", GOOD_PASSWORD)
        runCurrent()
        assertEquals(listOf(FakeSettingsCredentials.VERIFY, FakeSettingsCredentials.DELETE), h.credentials.calls)
        assertEquals(listOf(GOOD_PASSWORD), h.credentials.passwords)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.ReplaceAll(Route.SignIn)), o.events)
        assertNull(o.state.error)
    }

    @Test
    fun aLocalJournalChecksThePasswordThenErasesAndGoesToWelcome() = runTest {
        h.account.modeState.value = AccountMode.LOCAL
        val o = open()
        assertTrue(o.state.local)
        o.confirmWith(USERNAME, GOOD_PASSWORD)
        runCurrent()
        assertEquals(listOf(FakeSettingsCredentials.VERIFY, FakeSettingsCredentials.ERASE), h.credentials.calls)
        assertEquals(listOf<SettingsEvent>(SettingsEvent.ReplaceAll(Route.Welcome)), o.events)
    }

    @Test
    fun aWrongPasswordDeletesNothing() = runTest {
        val o = open()
        o.confirmWith(USERNAME, "not it")
        runCurrent()
        assertEquals(listOf(FakeSettingsCredentials.VERIFY), h.credentials.calls)
        assertEquals(DeleteAccountError.WRONG_PASSWORD, o.state.error)
        assertFalse(o.state.busy)
        assertEquals(emptyList<SettingsEvent>(), o.events)
    }

    @Test
    fun aFailureSaysItCouldNotSaveAndStaysOnTheScreen() = runTest {
        h.credentials.deletionFailure = NetworkError()
        val o = open()
        o.confirmWith(USERNAME, GOOD_PASSWORD)
        runCurrent()
        assertEquals(DeleteAccountError.FAILED, o.state.error)
        assertEquals(emptyList<SettingsEvent>(), o.events)
        h.credentials.deletionFailure = null
        h.credentials.verifyFailure = IllegalStateException("boom")
        val again = open()
        again.confirmWith(USERNAME, GOOD_PASSWORD)
        runCurrent()
        assertEquals(DeleteAccountError.FAILED, again.state.error)
    }

    @Test
    fun whileCheckingTheFormIsBusyAndASecondConfirmIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        h.credentials.gate = gate
        val o = open()
        o.confirmWith(USERNAME, GOOD_PASSWORD)
        runCurrent()
        assertTrue(o.state.busy)
        o.vm.onAction(DeleteAccountAction.Confirm)
        runCurrent()
        assertEquals(listOf(FakeSettingsCredentials.VERIFY), h.credentials.calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(FakeSettingsCredentials.VERIFY, FakeSettingsCredentials.DELETE), h.credentials.calls)
    }

    @Test
    fun aNewAttemptClearsTheOldError() = runTest {
        val o = open()
        o.confirmWith(USERNAME, "not it")
        runCurrent()
        assertEquals(DeleteAccountError.WRONG_PASSWORD, o.state.error)
        val gate = CompletableDeferred<Unit>()
        h.credentials.gate = gate
        o.vm.onAction(DeleteAccountAction.SetPassword(GOOD_PASSWORD))
        o.vm.onAction(DeleteAccountAction.Confirm)
        runCurrent()
        assertNull(o.state.error)
        gate.complete(Unit)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h1600dp-mdpi")
class PasswordAndDeleteScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = SettingsHarness()

    private fun await(tag: String) {
        compose.waitUntil(WAIT_MS) { compose.exists(tag) }
    }

    private fun click(tag: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun type(tag: String, value: String) {
        await(tag)
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(value)
        compose.waitForIdle()
    }

    private fun assertInOrder(tags: List<String>) {
        for (tag in tags) assertTrue(tag, compose.exists(tag))
        val tops = tags.map { compose.topOf(it) }
        assertEquals(tags.toString(), tops.sorted(), tops)
    }

    private fun openPassword(navigator: RecordingNavigator = RecordingNavigator(listOf(Route.SettingsAccount, Route.SettingsPassword))) {
        compose.showWith(navigator) { PasswordScreen(viewModel = PasswordViewModel(h.accountDeps)) }
        await(TestTags.Settings.PASSWORD_SCREEN)
    }

    private fun openDelete(navigator: RecordingNavigator = RecordingNavigator(listOf(Route.SettingsAccount, Route.SettingsDeleteAccount))) {
        compose.showWith(navigator) { DeleteAccountScreen(viewModel = DeleteAccountViewModel(h.accountDeps)) }
        await(TestTags.Settings.DELETE_SCREEN)
    }

    @Test
    fun changePasswordShowsTheV031FieldsAndADisabledSubmit() {
        openPassword()
        assertTrue(compose.showsText(text(R.string.settings_password_title)))
        assertInOrder(listOf(TestTags.Settings.CURRENT_PASSWORD, TestTags.Settings.NEW_PASSWORD, TestTags.Settings.CONFIRM_PASSWORD, TestTags.Settings.PASSWORD_SUBMIT))
        compose.onNodeWithTag(TestTags.Settings.CURRENT_PASSWORD).assert(labeled(text(R.string.settings_current_password)))
        compose.onNodeWithTag(TestTags.Settings.NEW_PASSWORD).assert(labeled(text(R.string.settings_new_password)))
        compose.onNodeWithTag(TestTags.Settings.CONFIRM_PASSWORD).assert(labeled(text(R.string.settings_confirm_password)))
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_SUBMIT).assert(labeled(text(R.string.settings_password_submit))).assertIsNotEnabled()
        for (tag in listOf(TestTags.Settings.PASSWORD_RESULT, TestTags.Settings.PASSWORD_SECURING, TestTags.Settings.PASSWORD_MISMATCH, TestTags.Password.TOO_SHORT)) {
            assertFalse(tag, compose.exists(tag))
        }
    }

    @Test
    fun theRuleHintsAndTheMismatchUseTheV031Ids() {
        openPassword()
        type(TestTags.Settings.NEW_PASSWORD, "short")
        await(TestTags.Password.TOO_SHORT)
        compose.onNodeWithTag(TestTags.Password.TOO_SHORT, useUnmergedTree = true).assert(contains(text(R.string.password_too_short)))
        type(TestTags.Settings.CONFIRM_PASSWORD, "shirt")
        await(TestTags.Settings.PASSWORD_MISMATCH)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_MISMATCH, useUnmergedTree = true).assert(contains(text(R.string.settings_password_mismatch)))
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun aValidFormChangesThePasswordAndShowsTheV031Result() {
        openPassword()
        type(TestTags.Settings.CURRENT_PASSWORD, GOOD_PASSWORD)
        type(TestTags.Settings.NEW_PASSWORD, NEW_PASSWORD)
        type(TestTags.Settings.CONFIRM_PASSWORD, NEW_PASSWORD)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_SUBMIT).assertIsEnabled()
        click(TestTags.Settings.PASSWORD_SUBMIT)
        await(TestTags.Settings.PASSWORD_RESULT)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_RESULT).assert(contains(text(R.string.settings_password_changed)))
        assertEquals(listOf("${FakeSettingsCredentials.CHANGE_PASSWORD}:$GOOD_PASSWORD:$NEW_PASSWORD"), h.credentials.calls)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun whileSecuringTheV031SecuringLineShows() {
        val gate = CompletableDeferred<Unit>()
        h.credentials.gate = gate
        openPassword()
        type(TestTags.Settings.CURRENT_PASSWORD, GOOD_PASSWORD)
        type(TestTags.Settings.NEW_PASSWORD, NEW_PASSWORD)
        type(TestTags.Settings.CONFIRM_PASSWORD, NEW_PASSWORD)
        click(TestTags.Settings.PASSWORD_SUBMIT)
        await(TestTags.Settings.PASSWORD_SECURING)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_SECURING, useUnmergedTree = true).assert(contains(text(R.string.settings_password_securing)))
        gate.complete(Unit)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Settings.PASSWORD_SECURING) }
        await(TestTags.Settings.PASSWORD_RESULT)
    }

    @Test
    fun aWrongCurrentPasswordShowsTheV031Error() {
        h.credentials.changeFailure = AuthError(AuthFailure.WRONG_PASSWORD)
        openPassword()
        type(TestTags.Settings.CURRENT_PASSWORD, "not it")
        type(TestTags.Settings.NEW_PASSWORD, NEW_PASSWORD)
        type(TestTags.Settings.CONFIRM_PASSWORD, NEW_PASSWORD)
        click(TestTags.Settings.PASSWORD_SUBMIT)
        await(TestTags.Settings.PASSWORD_RESULT)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_RESULT).assert(contains(text(R.string.settings_password_wrong)))
    }

    @Test
    fun anotherFailureShowsTheV031CouldNotSaveText() {
        h.credentials.changeFailure = NetworkError()
        openPassword()
        type(TestTags.Settings.CURRENT_PASSWORD, GOOD_PASSWORD)
        type(TestTags.Settings.NEW_PASSWORD, NEW_PASSWORD)
        type(TestTags.Settings.CONFIRM_PASSWORD, NEW_PASSWORD)
        click(TestTags.Settings.PASSWORD_SUBMIT)
        await(TestTags.Settings.PASSWORD_RESULT)
        compose.onNodeWithTag(TestTags.Settings.PASSWORD_RESULT).assert(contains(text(R.string.admin_failed)))
    }

    @Test
    fun deleteAccountAsksForTheUsernameAndThePasswordWithTheGracePeriod() {
        openDelete()
        assertTrue(compose.showsText(text(R.string.settings_delete_title)))
        assertTrue(compose.showsText(text(R.string.settings_delete_text, 7)))
        assertInOrder(listOf(TestTags.Settings.DELETE_USERNAME, TestTags.Settings.DELETE_PASSWORD, TestTags.Settings.DELETE_CONFIRM))
        compose.onNodeWithTag(TestTags.Settings.DELETE_USERNAME).assert(labeled(text(R.string.settings_delete_type_username)))
        compose.onNodeWithTag(TestTags.Settings.DELETE_PASSWORD).assert(labeled(text(R.string.settings_delete_password)))
        compose.onNodeWithTag(TestTags.Settings.DELETE_CONFIRM).assert(labeled(text(R.string.settings_delete_action))).assertIsNotEnabled()
        type(TestTags.Settings.DELETE_USERNAME, USERNAME)
        compose.onNodeWithTag(TestTags.Settings.DELETE_CONFIRM).assertIsNotEnabled()
        type(TestTags.Settings.DELETE_PASSWORD, GOOD_PASSWORD)
        compose.onNodeWithTag(TestTags.Settings.DELETE_CONFIRM).assertIsEnabled()
    }

    @Test
    fun confirmingDeletionGoesToSignIn() {
        val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsAccount, Route.SettingsDeleteAccount))
        openDelete(navigator)
        type(TestTags.Settings.DELETE_USERNAME, USERNAME)
        type(TestTags.Settings.DELETE_PASSWORD, GOOD_PASSWORD)
        click(TestTags.Settings.DELETE_CONFIRM)
        compose.waitUntil(WAIT_MS) { navigator.replacedAll.isNotEmpty() }
        assertEquals(listOf<Route>(Route.SignIn), navigator.replacedAll)
        assertEquals(listOf(FakeSettingsCredentials.VERIFY, FakeSettingsCredentials.DELETE), h.credentials.calls)
    }

    @Test
    fun aWrongPasswordShowsTheV031ErrorAndDeletesNothing() {
        openDelete()
        type(TestTags.Settings.DELETE_USERNAME, USERNAME)
        type(TestTags.Settings.DELETE_PASSWORD, "not it")
        click(TestTags.Settings.DELETE_CONFIRM)
        await(TestTags.Settings.ERROR)
        compose.onNodeWithTag(TestTags.Settings.ERROR).assert(contains(text(R.string.settings_password_wrong)))
        assertEquals(listOf(FakeSettingsCredentials.VERIFY), h.credentials.calls)
    }

    @Test
    fun aLocalJournalIsErasedWithTheEraseTexts() {
        h.account.modeState.value = AccountMode.LOCAL
        val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsAccount, Route.SettingsDeleteAccount))
        openDelete(navigator)
        assertTrue(compose.showsText(text(R.string.settings_erase)))
        assertTrue(compose.showsText(text(R.string.settings_erase_text)))
        assertFalse(compose.showsText(text(R.string.settings_delete_text, 7)))
        compose.onNodeWithTag(TestTags.Settings.DELETE_CONFIRM).assert(labeled(text(R.string.settings_erase_action)))
        type(TestTags.Settings.DELETE_USERNAME, USERNAME)
        type(TestTags.Settings.DELETE_PASSWORD, GOOD_PASSWORD)
        click(TestTags.Settings.DELETE_CONFIRM)
        compose.waitUntil(WAIT_MS) { navigator.replacedAll.isNotEmpty() }
        assertEquals(listOf<Route>(Route.Welcome), navigator.replacedAll)
        assertEquals(listOf(FakeSettingsCredentials.VERIFY, FakeSettingsCredentials.ERASE), h.credentials.calls)
    }

    @Test
    fun theBackArrowsLeaveTheScreens() {
        val navigator = RecordingNavigator(listOf(Route.SettingsAccount, Route.SettingsPassword))
        openPassword(navigator)
        click(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
