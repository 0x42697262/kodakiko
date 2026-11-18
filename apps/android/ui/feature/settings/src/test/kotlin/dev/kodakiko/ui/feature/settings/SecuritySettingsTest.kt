@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val AUTO_LOCK_CHOICES = listOf(1, 2, 5, 10, 15, 30, 60)
private const val KEEP_UNLOCKED = "settings.keepUnlocked"

@RunWith(RobolectricTestRunner::class)
class SecuritySettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private class Opened(val vm: SecuritySettingsViewModel, val events: MutableList<SettingsEvent>) {
        val state: SecuritySettingsState get() = vm.state.value
    }

    private fun TestScope.open(): Opened {
        val vm = SecuritySettingsViewModel(h.deps)
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return Opened(vm, events)
    }

    @Test
    fun theStateCarriesTheSettingsTheBiometricAnswerAndTheThumbnailSwitch() = runTest {
        val o = open()
        assertEquals(defaults, o.state.settings)
        assertTrue(o.state.biometric)
        assertEquals(ThumbnailModeSwitch.IDLE, o.state.modeSwitch)
        assertFalse(o.state.confirmingThumbnails)
        h.thumbnails.modeSwitchState.value = ThumbnailModeSwitch(running = true, done = 3, total = 10)
        runCurrent()
        assertEquals(ThumbnailModeSwitch(running = true, done = 3, total = 10), o.state.modeSwitch)
    }

    @Test
    fun aDeviceWithoutAScreenLockReportsNoBiometric() = runTest {
        h.biometric = false
        assertFalse(open().state.biometric)
    }

    @Test
    fun autoLockSecureScreenAndHidePreviewsWriteOnlyTheirFields() = runTest {
        val o = open()
        o.vm.onAction(SecuritySettingsAction.SetAutoLock(1))
        runCurrent()
        assertEquals(defaults.copy(autoLockMinutes = 1), h.store.value)
        o.vm.onAction(SecuritySettingsAction.SetSecureScreen(false))
        runCurrent()
        assertEquals(defaults.copy(autoLockMinutes = 1, secureScreen = false), h.store.value)
        o.vm.onAction(SecuritySettingsAction.SetHideNotePreviews(true))
        runCurrent()
        assertEquals(defaults.copy(autoLockMinutes = 1, secureScreen = false, hideNotePreviews = true), h.store.value)
        assertEquals(h.store.value, o.state.settings)
        assertEquals(emptyList<Boolean>(), h.thumbnails.switches)
    }

    @Test
    fun theThumbnailSwitchAsksFirstAndChangesNothingUntilConfirmed() = runTest {
        val o = open()
        o.vm.onAction(SecuritySettingsAction.AskThumbnails)
        runCurrent()
        assertTrue(o.state.confirmingThumbnails)
        assertFalse(h.store.value.encryptThumbnails)
        assertEquals(emptyList<Boolean>(), h.thumbnails.switches)
        o.vm.onAction(SecuritySettingsAction.DismissThumbnails)
        runCurrent()
        assertFalse(o.state.confirmingThumbnails)
        assertEquals(defaults, h.store.value)
        assertEquals(emptyList<Boolean>(), h.thumbnails.switches)
    }

    @Test
    fun confirmingSavesTheNewModeFirstAndThenRebuildsTheThumbnails() = runTest {
        val o = open()
        o.vm.onAction(SecuritySettingsAction.AskThumbnails)
        o.vm.onAction(SecuritySettingsAction.ConfirmThumbnails)
        runCurrent()
        assertFalse(o.state.confirmingThumbnails)
        assertEquals(defaults.copy(encryptThumbnails = true), h.store.value)
        assertEquals(listOf(true), h.thumbnails.switches)
        assertEquals(listOf(true), h.thumbnails.settingAtSwitch)
    }

    @Test
    fun confirmingWhileEncryptedSwitchesBackToPlainThumbnails() = runTest {
        h.store.set(defaults.copy(encryptThumbnails = true))
        val o = open()
        o.vm.onAction(SecuritySettingsAction.AskThumbnails)
        o.vm.onAction(SecuritySettingsAction.ConfirmThumbnails)
        runCurrent()
        assertEquals(defaults, h.store.value)
        assertEquals(listOf(false), h.thumbnails.switches)
        assertEquals(listOf(false), h.thumbnails.settingAtSwitch)
    }

    @Test
    fun backLeavesTheScreen() = runTest {
        val o = open()
        o.vm.onAction(SecuritySettingsAction.Back)
        runCurrent()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.Back), o.events)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class SecuritySettingsScreenTest : PreferenceScreenHarness() {
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsSecurity))

    private fun open() {
        compose.showWith(navigator) { SecuritySettingsScreen(viewModel = SecuritySettingsViewModel(h.deps)) }
        await(TestTags.Settings.SECURITY_SCREEN)
        await(TestTags.Settings.AUTO_LOCK)
    }

    @Test
    fun theRowsFollowTheV031AndroidOrderWithoutTheWebOnlyKeepUnlockedRow() {
        open()
        assertTrue(compose.showsText(text(R.string.prefs_security)))
        assertInOrder(
            listOf(
                TestTags.Settings.AUTO_LOCK,
                TestTags.Settings.BIOMETRIC,
                TestTags.Settings.SECURE_SCREEN,
                TestTags.Settings.ENCRYPT_THUMBNAILS,
                TestTags.Settings.HIDE_NOTE_PREVIEWS,
            ),
        )
        assertFalse(compose.exists(KEEP_UNLOCKED))
        assertFalse(compose.exists(TestTags.Settings.THUMBNAIL_PROGRESS))
        assertFalse(compose.exists(TestTags.Settings.THUMBNAIL_SHEET))
    }

    @Test
    fun theTitlesAndSummariesAreTheV031AndroidTexts() {
        open()
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.settings_biometric_on)) }
        compose.onNodeWithTag(TestTags.Settings.AUTO_LOCK)
            .assert(contains(text(R.string.settings_auto_lock)))
            .assert(contains(plural(R.plurals.settings_auto_lock_background, 5, 5)))
        compose.onNodeWithTag(TestTags.Settings.BIOMETRIC)
            .assert(contains(text(R.string.settings_biometric)))
            .assert(contains(text(R.string.settings_biometric_on)))
        compose.onNodeWithTag(TestTags.Settings.SECURE_SCREEN)
            .assert(contains(text(R.string.prefs_secure_screen)))
            .assert(contains(text(R.string.prefs_secure_screen_summary)))
        compose.onNodeWithTag(TestTags.Settings.ENCRYPT_THUMBNAILS)
            .assert(contains(text(R.string.settings_encrypt_thumbnails)))
            .assert(contains(text(R.string.settings_encrypt_thumbnails_hint)))
        compose.onNodeWithTag(TestTags.Settings.HIDE_NOTE_PREVIEWS).assert(contains(text(R.string.prefs_hide_note_previews)))
    }

    @Test
    fun withoutAScreenLockTheBiometricRowSaysHowToSetOneUp() {
        h.biometric = false
        open()
        compose.waitUntil(WAIT_MS) { compose.showsText(text(R.string.settings_biometric_off)) }
        compose.onNodeWithTag(TestTags.Settings.BIOMETRIC).assert(contains(text(R.string.settings_biometric_off)))
    }

    @Test
    fun autoLockOffersTheV031MinutesAndWritesTheChoiceLikeU15() {
        open()
        click(TestTags.Settings.AUTO_LOCK)
        await(SettingsTestTags.dialog(TestTags.Settings.AUTO_LOCK))
        for (minutes in AUTO_LOCK_CHOICES) {
            compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, minutes)).performScrollTo()
                .assert(labeled(plural(R.plurals.prefs_auto_lock_minutes, minutes, minutes)))
        }
        assertFalse(compose.exists(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, 0)))
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, 5)).assert(selected())
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, 1)).performScrollTo()
        tap(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, 1))
        compose.waitUntil(WAIT_MS) { !compose.exists(SettingsTestTags.dialog(TestTags.Settings.AUTO_LOCK)) }
        assertOnlyChanged(defaults, defaults.copy(autoLockMinutes = 1))
        compose.onNodeWithTag(TestTags.Settings.AUTO_LOCK).assert(contains(plural(R.plurals.settings_auto_lock_background, 1, 1)))
        chooses(TestTags.Settings.AUTO_LOCK, 60, 60) { it.autoLockMinutes }
    }

    @Test
    fun secureScreenAndHidePreviewsWriteTheirFields() {
        open()
        toggles(TestTags.Settings.SECURE_SCREEN) { it.secureScreen }
        toggles(TestTags.Settings.HIDE_NOTE_PREVIEWS) { it.hideNotePreviews }
        assertEquals(defaults, h.store.value)
        click(TestTags.Settings.HIDE_NOTE_PREVIEWS)
        assertOnlyChanged(defaults, defaults.copy(hideNotePreviews = true))
    }

    @Test
    fun theThumbnailSwitchOpensTheV031QuestionAndContinueRebuildsEncrypted() {
        open()
        compose.onNodeWithTag(TestTags.Settings.ENCRYPT_THUMBNAILS).assert(unchecked())
        click(TestTags.Settings.ENCRYPT_THUMBNAILS)
        await(TestTags.Settings.THUMBNAIL_SHEET)
        assertTrue(compose.showsText(text(R.string.settings_encrypt_thumbnails_confirm)))
        assertEquals(defaults, h.store.value)
        assertEquals(emptyList<Boolean>(), h.thumbnails.switches)
        compose.onNodeWithTag(TestTags.Settings.THUMBNAIL_CONFIRM).assert(labeled(text(R.string.settings_confirm)))
        tap(TestTags.Settings.THUMBNAIL_CONFIRM)
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Settings.THUMBNAIL_SHEET) }
        compose.waitUntil(WAIT_MS) { h.thumbnails.switches.isNotEmpty() }
        assertOnlyChanged(defaults, defaults.copy(encryptThumbnails = true))
        assertEquals(listOf(true), h.thumbnails.switches)
        assertEquals(listOf(true), h.thumbnails.settingAtSwitch)
        compose.onNodeWithTag(TestTags.Settings.ENCRYPT_THUMBNAILS).assert(checked())
    }

    @Test
    fun whileThumbnailsAreRebuiltTheProgressShowsAndTheSwitchIsDisabled() {
        open()
        h.thumbnails.modeSwitchState.value = ThumbnailModeSwitch(running = true, done = 3, total = 10)
        await(TestTags.Settings.THUMBNAIL_PROGRESS)
        compose.onNodeWithTag(TestTags.Settings.THUMBNAIL_PROGRESS, useUnmergedTree = true)
            .assertTextEquals(text(R.string.settings_encrypt_thumbnails_progress, 3, 10))
        compose.onNodeWithTag(TestTags.Settings.ENCRYPT_THUMBNAILS).assertIsNotEnabled()
        h.thumbnails.modeSwitchState.value = ThumbnailModeSwitch(running = true, done = 10, total = 10)
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.Settings.THUMBNAIL_PROGRESS, useUnmergedTree = true)
            .assertTextEquals(text(R.string.settings_encrypt_thumbnails_progress, 10, 10))
        h.thumbnails.modeSwitchState.value = ThumbnailModeSwitch.IDLE
        compose.waitUntil(WAIT_MS) { !compose.exists(TestTags.Settings.THUMBNAIL_PROGRESS) }
        compose.onNodeWithTag(TestTags.Settings.ENCRYPT_THUMBNAILS).assertIsEnabled()
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        open()
        tap(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-mdpi")
class AutoLockFromTheRootTest : PreferenceScreenHarness() {
    @Test
    fun theU15PathSecurityThenAutoLockThenOneMinuteSavesOneMinute() {
        val host = SettingsTestHost(h, listOf(Route.More, Route.Settings))
        compose.show { host.Content() }
        await(TestTags.Settings.SCREEN)
        click(TestTags.Settings.ROW_SECURITY)
        await(TestTags.Settings.SECURITY_SCREEN)
        assertEquals(listOf<Route>(Route.SettingsSecurity), host.pushed)
        click(TestTags.Settings.AUTO_LOCK)
        await(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, 1))
        tap(SettingsTestTags.option(TestTags.Settings.AUTO_LOCK, 1))
        compose.waitUntil(WAIT_MS) { h.store.value.autoLockMinutes == 1 }
        assertOnlyChanged(defaults, defaults.copy(autoLockMinutes = 1))
    }
}
