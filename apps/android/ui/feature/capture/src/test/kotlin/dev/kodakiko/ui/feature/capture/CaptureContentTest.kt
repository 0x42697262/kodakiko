package dev.kodakiko.ui.feature.capture

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-mdpi")
class CaptureContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<CaptureAction>()
    private val preview = FakePreview()

    private fun show(state: CaptureState) {
        compose.show(preview = preview) { CaptureContent(state = state, onAction = { actions += it }) }
    }

    private fun merged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun holds(text: String): SemanticsMatcher = hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))

    private fun unmerged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun click(tag: String) {
        merged(tag).performClick()
        compose.waitForIdle()
    }

    private fun keepsScreenOn(): Boolean = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun theCameraScreenShowsThePreviewTheControlsAndNext() {
        show(CaptureStates.idle)
        listOf(
            TestTags.Capture.SCREEN,
            TestTags.Capture.CAMERA,
            TestTags.Capture.SHUTTER,
            TestTags.Capture.MODE_PHOTO,
            TestTags.Capture.MODE_VIDEO,
            TestTags.Capture.PICK_FROM_GALLERY,
            TestTags.Capture.FLIP,
            TestTags.Capture.CLOSE,
            TestTags.Capture.NEXT,
        ).forEach { assertTrue(it, compose.exists(it)) }
        listOf(
            TestTags.Capture.ADD_TO_ENTRY,
            TestTags.Capture.TIMER,
            TestTags.Capture.REFUSALS,
            TestTags.Capture.DISCARD_PANEL,
            TestTags.Capture.CAMERA_PERMISSION_BODY,
            TestTags.Capture.ALLOW_CAMERA,
            TestTags.Capture.OPEN_SETTINGS,
            TestTags.Capture.SYSTEM_PHOTO,
            TestTags.Capture.SYSTEM_VIDEO,
            listItem(TestTags.Capture.TRAY_ITEM, 0),
            listItem(TestTags.Capture.IMPORTING, 0),
        ).forEach { assertFalse(it, compose.exists(it)) }
        assertEquals(CameraFacing.BACK, preview.facings.last())
        merged(TestTags.Capture.SHUTTER).assertContentDescriptionEquals("Take a photo, or hold to record a video")
        merged(TestTags.Capture.CLOSE).assertContentDescriptionEquals("Close")
        merged(TestTags.Capture.FLIP).assertContentDescriptionEquals("Flip Camera")
        merged(TestTags.Capture.PICK_FROM_GALLERY).assertContentDescriptionEquals("Gallery")
        unmerged(TestTags.Capture.NEXT).assert(labeled("Next"))
        merged(TestTags.Capture.MODE_PHOTO).assert(hasText("Photo"))
        merged(TestTags.Capture.MODE_VIDEO).assert(hasText("Video"))
    }

    @Test
    fun thePreviewFollowsTheChosenCamera() {
        show(CaptureStates.state(facing = CameraFacing.FRONT))
        assertEquals(CameraFacing.FRONT, preview.facings.last())
    }

    @Test
    fun theModeSwitchShowsTheModeAndSendsTheOtherOne() {
        show(CaptureStates.idle)
        merged(TestTags.Capture.MODE_PHOTO).assertIsSelected()
        merged(TestTags.Capture.MODE_VIDEO).assertIsNotSelected()
        click(TestTags.Capture.MODE_VIDEO)
        assertEquals(listOf<CaptureAction>(CaptureAction.SetMode(CaptureMode.VIDEO)), actions)
    }

    @Test
    fun videoModeIsShownAsSelected() {
        show(CaptureStates.state(mode = CaptureMode.VIDEO))
        merged(TestTags.Capture.MODE_VIDEO).assertIsSelected()
        merged(TestTags.Capture.MODE_PHOTO).assertIsNotSelected()
        click(TestTags.Capture.MODE_PHOTO)
        assertEquals(listOf<CaptureAction>(CaptureAction.SetMode(CaptureMode.PHOTO)), actions)
    }

    @Test
    fun theShutterReportsPressAndReleaseSoTheModelCanTellATapFromAHold() {
        show(CaptureStates.idle)
        merged(TestTags.Capture.SHUTTER).performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf<CaptureAction>(CaptureAction.ShutterDown), actions)
        merged(TestTags.Capture.SHUTTER).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(CaptureAction.ShutterDown, CaptureAction.ShutterUp), actions)
    }

    @Test
    fun aCancelledPressStillReleasesTheShutter() {
        show(CaptureStates.idle)
        merged(TestTags.Capture.SHUTTER).performTouchInput {
            down(center)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(listOf(CaptureAction.ShutterDown, CaptureAction.ShutterUp), actions)
    }

    @Test
    fun anAccessibilityClickOnTheShutterIsATap() {
        show(CaptureStates.idle)
        merged(TestTags.Capture.SHUTTER).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(CaptureAction.ShutterDown, CaptureAction.ShutterUp), actions)
    }

    @Test
    fun flipGalleryAndCloseSendTheirActions() {
        show(CaptureStates.idle)
        click(TestTags.Capture.FLIP)
        click(TestTags.Capture.PICK_FROM_GALLERY)
        click(TestTags.Capture.CLOSE)
        assertEquals(listOf(CaptureAction.Flip, CaptureAction.PickFromGallery, CaptureAction.Close), actions)
    }

    @Test
    fun systemBackActsLikeClose() {
        show(CaptureStates.withTray)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf<CaptureAction>(CaptureAction.Close), actions)
    }

    @Test
    fun whileRecordingTheTimerShowsAndTheModeSwitchFlipAndGalleryAreDisabled() {
        show(CaptureStates.recording)
        merged(TestTags.Capture.TIMER).assertTextEquals("01:03")
        merged(TestTags.Capture.MODE_PHOTO).assertIsNotEnabled()
        merged(TestTags.Capture.MODE_VIDEO).assertIsNotEnabled()
        merged(TestTags.Capture.FLIP).assertIsNotEnabled()
        merged(TestTags.Capture.PICK_FROM_GALLERY).assertIsNotEnabled()
        merged(TestTags.Capture.SHUTTER).assertIsEnabled()
        merged(TestTags.Capture.NEXT).assertIsNotEnabled()
        click(TestTags.Capture.FLIP)
        click(TestTags.Capture.PICK_FROM_GALLERY)
        click(TestTags.Capture.MODE_PHOTO)
        click(TestTags.Capture.NEXT)
        assertEquals(emptyList<CaptureAction>(), actions)
    }

    @Test
    fun theTrayShowsEachItemWithARemoveButtonAndASpinnerPerPendingImport() {
        show(CaptureStates.withTray)
        (0 until 3).forEach { assertTrue(compose.exists(listItem(TestTags.Capture.TRAY_ITEM, it))) }
        assertFalse(compose.exists(listItem(TestTags.Capture.TRAY_ITEM, 3)))
        (0 until 2).forEach { assertTrue(compose.exists(listItem(TestTags.Capture.IMPORTING, it))) }
        assertFalse(compose.exists(listItem(TestTags.Capture.IMPORTING, 2)))
        merged(listItem(TestTags.Capture.REMOVE_ITEM, 1)).assertContentDescriptionEquals("Remove item 2")
        click(listItem(TestTags.Capture.REMOVE_ITEM, 1))
        assertEquals(listOf<CaptureAction>(CaptureAction.Remove(mediaId("tray1"))), actions)
    }

    @Test
    fun nextIsDisabledWithAnEmptyTrayAndOpensTheEntryOtherwise() {
        val state = mutableStateOf(CaptureStates.idle)
        compose.show(preview = preview) { CaptureContent(state = state.value, onAction = { actions += it }) }
        merged(TestTags.Capture.NEXT).assertIsNotEnabled()
        click(TestTags.Capture.NEXT)
        assertEquals(emptyList<CaptureAction>(), actions)
        state.value = CaptureStates.state(items = CaptureStates.items(1), pending = 1)
        compose.waitForIdle()
        merged(TestTags.Capture.NEXT).assertIsEnabled()
        click(TestTags.Capture.NEXT)
        assertEquals(listOf<CaptureAction>(CaptureAction.Next), actions)
    }

    @Test
    fun addingToAnEntryShowsAddToEntryWhichWaitsForImports() {
        val state = mutableStateOf(CaptureStates.state(addToEntry = true, items = CaptureStates.items(2), pending = 1))
        compose.show(preview = preview) { CaptureContent(state = state.value, onAction = { actions += it }) }
        assertFalse(compose.exists(TestTags.Capture.NEXT))
        unmerged(TestTags.Capture.ADD_TO_ENTRY).assert(labeled("Add to Entry"))
        merged(TestTags.Capture.ADD_TO_ENTRY).assertIsNotEnabled()
        state.value = CaptureStates.state(addToEntry = true, items = CaptureStates.items(2))
        compose.waitForIdle()
        merged(TestTags.Capture.ADD_TO_ENTRY).assertIsEnabled()
        click(TestTags.Capture.ADD_TO_ENTRY)
        assertEquals(listOf<CaptureAction>(CaptureAction.AddToEntry), actions)
        state.value = CaptureStates.state(addToEntry = true, items = CaptureStates.items(2), adding = true)
        compose.waitForIdle()
        merged(TestTags.Capture.ADD_TO_ENTRY).assertIsNotEnabled()
        click(TestTags.Capture.ADD_TO_ENTRY)
        assertEquals(listOf<CaptureAction>(CaptureAction.AddToEntry), actions)
    }

    @Test
    fun withoutPermissionTheGateExplainsWhyAndAsksForTheCamera() {
        show(CaptureStates.state(permission = CameraPermission.ASK))
        merged(TestTags.Capture.CAMERA_PERMISSION_BODY).assertTextEquals("Kodakiko needs the camera to take photos and videos.")
        merged(TestTags.Capture.ALLOW_CAMERA).assert(hasText("Allow Camera"))
        assertFalse(compose.exists(TestTags.Capture.OPEN_SETTINGS))
        assertFalse(compose.exists(TestTags.Capture.SHUTTER))
        assertFalse(compose.exists(TestTags.Capture.FLIP))
        assertFalse(compose.exists(TestTags.Capture.MODE_PHOTO))
        assertTrue(preview.facings.isEmpty())
        assertTrue(compose.exists(TestTags.Capture.PICK_FROM_GALLERY))
        assertTrue(compose.exists(TestTags.Capture.CLOSE))
        assertTrue(compose.exists(TestTags.Capture.NEXT))
        click(TestTags.Capture.ALLOW_CAMERA)
        assertEquals(listOf<CaptureAction>(CaptureAction.AllowCamera), actions)
    }

    @Test
    fun whenAndroidWillNotAskAgainTheGateOpensTheAppSettings() {
        show(CaptureStates.state(permission = CameraPermission.BLOCKED))
        merged(TestTags.Capture.CAMERA_PERMISSION_BODY).assertTextEquals("Kodakiko needs the camera to take photos and videos.")
        merged(TestTags.Capture.OPEN_SETTINGS).assert(hasText("Open Settings"))
        assertFalse(compose.exists(TestTags.Capture.ALLOW_CAMERA))
        click(TestTags.Capture.OPEN_SETTINGS)
        assertEquals(listOf<CaptureAction>(CaptureAction.OpenSettings), actions)
    }

    @Test
    fun thePhoneCameraAppReplacesTheBuiltInCameraWithTwoButtons() {
        show(CaptureStates.state(cameraApp = CameraApp.SYSTEM, permission = CameraPermission.ASK))
        assertTrue(compose.exists(TestTags.Capture.CAMERA))
        merged(TestTags.Capture.SYSTEM_PHOTO).assert(hasText("Take a photo with the camera app"))
        merged(TestTags.Capture.SYSTEM_VIDEO).assert(hasText("Record a video with the camera app"))
        listOf(
            TestTags.Capture.SHUTTER,
            TestTags.Capture.FLIP,
            TestTags.Capture.MODE_PHOTO,
            TestTags.Capture.CAMERA_PERMISSION_BODY,
            TestTags.Capture.ALLOW_CAMERA,
        ).forEach { assertFalse(it, compose.exists(it)) }
        assertTrue(preview.facings.isEmpty())
        assertTrue(compose.exists(TestTags.Capture.PICK_FROM_GALLERY))
        click(TestTags.Capture.SYSTEM_PHOTO)
        click(TestTags.Capture.SYSTEM_VIDEO)
        assertEquals(listOf(CaptureAction.PhoneCamera(video = false), CaptureAction.PhoneCamera(video = true)), actions)
    }

    @Test
    fun refusalsShowInOneBanner() {
        show(CaptureStates.refusals)
        listOf(
            "notes.pdf isn't a supported photo or video.",
            "clip.mp4 is larger than this server allows.",
            "An entry can hold up to 20 photos and videos.",
        ).forEach { unmerged(TestTags.Capture.REFUSALS).assert(holds(it)) }
        assertEquals(1, compose.count(TestTags.Capture.REFUSALS))
    }

    @Test
    fun closeWithItemsAsksToDiscardThemAndKeepCancels() {
        show(CaptureStates.state(items = CaptureStates.items(2), dialog = CaptureDialog.Discard(count = 2)))
        unmerged(TestTags.Capture.DISCARD_PANEL).assert(holds("Discard 2 items?"))
        merged(TestTags.Capture.DISCARD).assert(hasText("Discard"))
        merged(TestTags.Capture.KEEP).assert(hasText("Keep"))
        click(TestTags.Capture.KEEP)
        click(TestTags.Capture.DISCARD)
        assertEquals(listOf(CaptureAction.Keep, CaptureAction.Discard), actions)
    }

    @Test
    fun theDiscardQuestionCountsOneItemInTheSingular() {
        show(CaptureStates.state(items = CaptureStates.items(1), dialog = CaptureDialog.Discard(count = 1)))
        unmerged(TestTags.Capture.DISCARD_PANEL).assert(holds("Discard 1 item?"))
    }

    @Test
    fun itemsLeftFromAnotherCaptureAreOfferedForDiscardBeforeTheCameraOpens() {
        show(CaptureStates.state(addToEntry = true, dialog = CaptureDialog.Leftover(count = 2)))
        unmerged(TestTags.Capture.DISCARD_PANEL).assert(holds("Discard 2 items in progress?"))
        merged(TestTags.Capture.CANCEL_ADD).assert(hasText("Cancel"))
        assertFalse(compose.exists(TestTags.Capture.SHUTTER))
        assertFalse(compose.exists(TestTags.Capture.ADD_TO_ENTRY))
        assertTrue(preview.facings.isEmpty())
        click(TestTags.Capture.CANCEL_ADD)
        click(TestTags.Capture.DISCARD)
        assertEquals(listOf(CaptureAction.CancelAdd, CaptureAction.Discard), actions)
    }

    @Test
    fun theScreenStaysOnWhileShownWhenTheSettingIsOn() {
        val visible = mutableStateOf(true)
        val state = mutableStateOf(CaptureStates.idle)
        compose.show(preview = preview) {
            if (visible.value) CaptureContent(state = state.value, onAction = { actions += it })
        }
        assertTrue(keepsScreenOn())
        state.value = CaptureStates.state(keepScreenOn = false)
        compose.waitForIdle()
        assertFalse(keepsScreenOn())
        state.value = CaptureStates.idle
        compose.waitForIdle()
        assertTrue(keepsScreenOn())
        visible.value = false
        compose.waitForIdle()
        assertFalse(keepsScreenOn())
    }

    @Test
    fun theRefusalTextsUseTheTrayLimitOfTheRefusal() {
        val refusal = CaptureRefusal(ImportRefusal.TRAY_FULL, RefusalLabel.ThisVideo, 3)
        show(CaptureStates.state(refusals = listOf(refusal)))
        unmerged(TestTags.Capture.REFUSALS).assert(holds("An entry can hold up to 3 photos and videos."))
        assertTrue(compose.onAllNodes(hasContentDescription("Close"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
