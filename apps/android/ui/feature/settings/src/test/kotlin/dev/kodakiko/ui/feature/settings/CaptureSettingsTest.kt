@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.data.db.CameraSide
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class CaptureSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val h = SettingsHarness()

    private fun TestScope.open(): CaptureSettingsViewModel {
        val vm = CaptureSettingsViewModel(h.deps)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun turningLocationOnSavesItFirstAndThenTriesOnePositionForTenSeconds() = runTest {
        val vm = open()
        vm.onAction(CaptureSettingsAction.SetLocation(true))
        runCurrent()
        assertEquals(listOf(LOCATION_PROBE_MS), h.location.timeouts)
        assertEquals(listOf(true), h.location.settingAtProbe)
        assertTrue(h.store.value.locationForCaptures)
        assertFalse(vm.state.value.locationDenied)
        assertEquals(defaults.copy(locationForCaptures = true), vm.state.value.settings)
    }

    @Test
    fun noPositionTurnsLocationBackOffAndShowsThePermissionWarning() = runTest {
        h.location.result = false
        val vm = open()
        vm.onAction(CaptureSettingsAction.SetLocation(true))
        runCurrent()
        assertFalse(h.store.value.locationForCaptures)
        assertTrue(vm.state.value.locationDenied)
    }

    @Test
    fun turningLocationOffNeedsNoProbeAndClearsTheWarning() = runTest {
        h.location.result = false
        val vm = open()
        vm.onAction(CaptureSettingsAction.SetLocation(true))
        runCurrent()
        assertTrue(vm.state.value.locationDenied)
        vm.onAction(CaptureSettingsAction.SetLocation(false))
        runCurrent()
        assertFalse(vm.state.value.locationDenied)
        assertFalse(h.store.value.locationForCaptures)
        assertEquals(1, h.location.timeouts.size)
    }

    @Test
    fun tryingAgainHidesTheOldWarningWhileTheNewProbeRuns() = runTest {
        h.location.result = false
        val vm = open()
        vm.onAction(CaptureSettingsAction.SetLocation(true))
        runCurrent()
        h.location.result = true
        h.location.gate = CompletableDeferred()
        vm.onAction(CaptureSettingsAction.SetLocation(true))
        runCurrent()
        assertFalse(vm.state.value.locationDenied)
        assertTrue(h.store.value.locationForCaptures)
        h.location.gate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.locationDenied)
        assertTrue(h.store.value.locationForCaptures)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h2000dp-mdpi")
class CaptureSettingsScreenTest : PreferenceScreenHarness() {
    private val navigator = RecordingNavigator(listOf(Route.Settings, Route.SettingsCapture))

    private fun open() {
        compose.showWith(navigator) { CaptureSettingsScreen(viewModel = CaptureSettingsViewModel(h.deps)) }
        await(TestTags.Settings.CAPTURE_SCREEN)
    }

    @Test
    fun theRowsFollowTheV031OrderAndEndWithThePhoneGalleryNote() {
        open()
        assertInOrder(
            listOf(
                TestTags.Settings.LOCATION,
                TestTags.Settings.START_CAMERA,
                TestTags.Settings.CAMERA_APP,
                TestTags.Settings.HOLD_TO_RECORD,
                TestTags.Settings.HOLD_MAX,
                TestTags.Settings.KEEP_SCREEN_ON,
            ),
        )
        assertTrue(compose.showsText(text(R.string.prefs_no_phone_gallery)))
        assertTrue(compose.showsText(text(R.string.prefs_capture)))
        compose.onNodeWithTag(TestTags.Settings.LOCATION).assert(contains(text(R.string.settings_location)))
        compose.onNodeWithTag(TestTags.Settings.HOLD_TO_RECORD).assert(contains(text(R.string.prefs_hold_to_record_summary)))
        compose.onNodeWithTag(TestTags.Settings.HOLD_MAX).assert(contains(text(R.string.prefs_seconds, 15)))
        compose.onNodeWithTag(TestTags.Settings.START_CAMERA).assert(contains(text(R.string.prefs_camera_back)))
        compose.onNodeWithTag(TestTags.Settings.KEEP_SCREEN_ON).assert(contains(text(R.string.prefs_keep_screen_on)))
    }

    @Test
    fun aRefusedLocationShowsTheWarningAndLeavesTheSwitchOff() {
        h.location.result = false
        open()
        assertFalse(compose.exists(TestTags.Settings.LOCATION_DENIED))
        click(TestTags.Settings.LOCATION)
        await(TestTags.Settings.LOCATION_DENIED)
        compose.onNodeWithTag(TestTags.Settings.LOCATION_DENIED).assert(contains(text(R.string.settings_location_denied)))
        compose.onNodeWithTag(TestTags.Settings.LOCATION).assert(unchecked())
        assertFalse(h.store.value.locationForCaptures)
    }

    @Test
    fun aGrantedLocationStaysOn() {
        open()
        click(TestTags.Settings.LOCATION)
        compose.onNodeWithTag(TestTags.Settings.LOCATION).assert(checked())
        assertEquals(defaults.copy(locationForCaptures = true), h.store.value)
        assertFalse(compose.exists(TestTags.Settings.LOCATION_DENIED))
    }

    @Test
    fun startCameraWritesBackOrFront() {
        open()
        click(TestTags.Settings.START_CAMERA)
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.START_CAMERA, "back")).assert(labeled(text(R.string.prefs_camera_back)))
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.START_CAMERA, "front")).assert(labeled(text(R.string.prefs_camera_front)))
        tap(SettingsTestTags.option(TestTags.Settings.START_CAMERA, "front"))
        assertOnlyChanged(defaults, defaults.copy(captureStartCamera = CameraSide.FRONT))
    }

    @Test
    fun theCameraAppChoiceWarnsAboutThePhonesOwnCopyWhenThePhoneAppIsChosen() {
        open()
        compose.onNodeWithTag(TestTags.Settings.CAMERA_APP).assert(contains(text(R.string.prefs_camera_kodakiko)))
        click(TestTags.Settings.CAMERA_APP)
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.CAMERA_APP, "kodakiko")).assert(labeled(text(R.string.prefs_camera_kodakiko)))
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.CAMERA_APP, "system")).assert(labeled(text(R.string.prefs_camera_system)))
        tap(SettingsTestTags.option(TestTags.Settings.CAMERA_APP, "system"))
        assertOnlyChanged(defaults, defaults.copy(cameraApp = CameraApp.SYSTEM))
        compose.onNodeWithTag(TestTags.Settings.CAMERA_APP).performScrollTo()
            .assert(contains(text(R.string.prefs_camera_system) + " \u2014 " + text(R.string.prefs_camera_system_warning)))
    }

    @Test
    fun holdToRecordAndKeepScreenOnWriteTheirFields() {
        open()
        toggles(TestTags.Settings.HOLD_TO_RECORD) { it.holdToRecordOn }
        toggles(TestTags.Settings.KEEP_SCREEN_ON) { it.captureKeepScreenOn }
        assertEquals(defaults, h.store.value)
    }

    @Test
    fun theHoldLengthOffersTheV031Seconds() {
        open()
        click(TestTags.Settings.HOLD_MAX)
        for (seconds in listOf(5, 10, 15, 30, 60, 120)) {
            compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.HOLD_MAX, seconds)).assert(labeled(text(R.string.prefs_seconds, seconds)))
        }
        compose.onNodeWithTag(SettingsTestTags.option(TestTags.Settings.HOLD_MAX, 15)).assert(selected())
        tap(SettingsTestTags.option(TestTags.Settings.HOLD_MAX, 30))
        assertOnlyChanged(defaults, defaults.copy(holdToRecordMaxSeconds = 30))
        compose.onNodeWithTag(TestTags.Settings.HOLD_MAX).performScrollTo().assert(contains(text(R.string.prefs_seconds, 30)))
    }

    @Test
    fun theBackArrowLeavesTheScreen() {
        open()
        tap(TestTags.Settings.BACK)
        assertEquals(1, navigator.pops)
    }
}
