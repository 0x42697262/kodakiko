package dev.kodakiko.ui.feature.capture

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.platform.camera.CameraConstants
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.ImportSlots
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-mdpi")
class CaptureScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = CaptureHarness()
    private val preview = FakePreview()
    private val navigator = RecordingNavigator(listOf(Route.Gallery, Route.Capture()))

    private val shadow: ShadowActivity get() = shadowOf(compose.activity)

    private fun open(entryId: String? = null, mode: CaptureMode = CaptureMode.PHOTO, routes: RecordingNavigator = navigator): CaptureViewModel {
        val vm = CaptureViewModel(h.deps, entryId, mode)
        compose.show(preview = preview) {
            CompositionLocalProvider(LocalRouteNavigator provides routes) { CaptureScreen(viewModel = vm) }
        }
        return vm
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun tapShutter() {
        compose.onNodeWithTag(TestTags.Capture.SHUTTER).performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
    }

    private fun uri(name: String): Uri = Uri.parse("content://media/picker/0/$name")

    private fun nextForResult(): ShadowActivity.IntentForResult {
        val started = shadow.nextStartedActivityForResult
        assertNotNull("an activity was started for a result", started)
        return started
    }

    private fun deliver(request: Intent, resultCode: Int, data: Intent?) {
        compose.runOnUiThread { shadow.receiveResult(request, resultCode, data) }
        compose.waitForIdle()
    }

    private fun pickerResult(vararg uris: Uri): Intent = Intent().apply {
        val first = uris.first()
        clipData = ClipData.newRawUri(null, first).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    }

    private fun trayCount(): Int = (0 until MAX_CHECKED).count { compose.exists(listItem(TestTags.Capture.TRAY_ITEM, it)) }

    @Test
    fun galleryOpensThePhotoPickerForPhotosAndVideosLimitedToTheSlotsLeftAndFillsTheTray() {
        open()
        click(TestTags.Capture.PICK_FROM_GALLERY)
        val started = nextForResult()
        assertEquals(MediaStore.ACTION_PICK_IMAGES, started.intent.action)
        assertEquals(20, started.intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1))
        deliver(started.intent, Activity.RESULT_OK, pickerResult(uri("a.jpg"), uri("b.mp4")))
        assertEquals(listOf(uri("a.jpg"), uri("b.mp4")), h.captureFiles.copied)
        assertEquals(listOf(picked("a.jpg"), picked("b.mp4")), h.imports.calls.map { it.input })
        assertTrue(h.imports.calls.all { it.input.origin == MediaOrigin.PICKER })
        assertEquals(2, trayCount())
        assertTrue(compose.exists(listItem(TestTags.Capture.TRAY_ITEM, 1)))
    }

    @Test
    fun u06TwoPickedFilesFillTheTrayAndNextOpensTheNewEntry() {
        open()
        click(TestTags.Capture.PICK_FROM_GALLERY)
        val started = nextForResult()
        deliver(started.intent, Activity.RESULT_OK, pickerResult(uri("photo-gps.jpg"), uri("video-20s.mp4")))
        assertTrue(compose.exists(listItem(TestTags.Capture.TRAY_ITEM, 1)))
        click(TestTags.Capture.NEXT)
        assertEquals(listOf(NavCall(RecordingNavigator.PUSH, Route.EntryNew)), navigator.calls)
        assertEquals(2, h.tray.items.size)
    }

    @Test
    fun withOneSlotLeftThePickerTakesASingleItem() {
        h.config.config = publicConfig(maxMedia = 3)
        h.tray.add(imported(mediaId("held1")))
        h.tray.add(imported(mediaId("held2")))
        open()
        click(TestTags.Capture.PICK_FROM_GALLERY)
        val started = nextForResult()
        assertEquals(MediaStore.ACTION_PICK_IMAGES, started.intent.action)
        assertFalse(started.intent.hasExtra(MediaStore.EXTRA_PICK_IMAGES_MAX))
        deliver(started.intent, Activity.RESULT_OK, Intent().setData(uri("c.jpg")))
        assertEquals(listOf(uri("c.jpg")), h.captureFiles.copied)
        assertEquals(ImportSlots(used = 2, limit = 3), h.imports.calls.single().slots)
        assertEquals(3, trayCount())
    }

    @Test
    fun withNoSlotsLeftNoPickerOpens() {
        h.config.config = publicConfig(maxMedia = 1)
        h.tray.add(imported(mediaId("held1")))
        open()
        click(TestTags.Capture.PICK_FROM_GALLERY)
        assertNull(shadow.nextStartedActivityForResult)
        assertNull(shadow.nextStartedActivity)
    }

    @Test
    fun aCancelledPickerAddsNothing() {
        open()
        click(TestTags.Capture.PICK_FROM_GALLERY)
        val started = nextForResult()
        deliver(started.intent, Activity.RESULT_CANCELED, null)
        assertEquals(emptyList<Uri>(), h.captureFiles.copied)
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertEquals(0, trayCount())
    }

    @Test
    fun thePhoneCameraAppWritesThePhotoIntoAFileProviderFileThatJoinsTheTray() {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM) }
        open()
        click(TestTags.Capture.SYSTEM_PHOTO)
        val started = nextForResult()
        val output = h.captureFiles.outputs.single()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.intent.action)
        val target = started.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        assertEquals(FileProvider.getUriForFile(context, AUTHORITY, output), target)
        assertTrue(started.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        deliver(started.intent, Activity.RESULT_OK, null)
        assertEquals(listOf(MediaInput(MediaOrigin.PICKER, output, null, null)), h.imports.calls.map { it.input })
        assertEquals(1, trayCount())
        assertEquals(emptyList<Long>(), h.location.requests)
    }

    @Test
    fun thePhoneCameraAppRecordsVideoIntoAnMp4File() {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM) }
        open()
        click(TestTags.Capture.SYSTEM_VIDEO)
        val started = nextForResult()
        val output = h.captureFiles.outputs.single()
        assertTrue(output.name.endsWith(".mp4"))
        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, started.intent.action)
        assertEquals(FileProvider.getUriForFile(context, AUTHORITY, output), started.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java))
        deliver(started.intent, Activity.RESULT_OK, null)
        assertEquals(listOf(output), h.imports.calls.map { it.input.file })
    }

    @Test
    fun aCancelledPhoneCaptureDeletesItsFileAndAddsNothing() {
        h.settings { it.copy(cameraApp = CameraApp.SYSTEM) }
        open()
        click(TestTags.Capture.SYSTEM_PHOTO)
        val started = nextForResult()
        val output = h.captureFiles.outputs.single()
        deliver(started.intent, Activity.RESULT_CANCELED, null)
        assertFalse(output.exists())
        assertEquals(emptyList<ImportCall>(), h.imports.calls)
        assertEquals(0, trayCount())
    }

    @Test
    fun u09APhotoATapRecordedVideoAndNextOpenTheNewEntry() {
        open()
        tapShutter()
        assertEquals(1, h.camera.photos)
        assertTrue(compose.exists(listItem(TestTags.Capture.TRAY_ITEM, 0)))
        click(TestTags.Capture.MODE_VIDEO)
        tapShutter()
        assertTrue(h.camera.recording)
        assertTrue(compose.exists(TestTags.Capture.TIMER))
        tapShutter()
        assertEquals(1, h.camera.stops)
        compose.runOnUiThread { h.camera.finish(cameraVideo()) }
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Capture.TIMER))
        assertTrue(compose.exists(listItem(TestTags.Capture.TRAY_ITEM, 1)))
        click(TestTags.Capture.NEXT)
        assertEquals(listOf(NavCall(RecordingNavigator.PUSH, Route.EntryNew)), navigator.calls)
    }

    @Test
    fun holdingTheShutterRecordsUntilItIsReleased() {
        open()
        compose.onNodeWithTag(TestTags.Capture.SHUTTER).performTouchInput { down(center) }
        compose.waitForIdle()
        ShadowLooper.idleMainLooper(CameraConstants.HOLD_TO_RECORD_MS, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        assertEquals(1, h.camera.starts.size)
        assertTrue(compose.exists(TestTags.Capture.TIMER))
        compose.onNodeWithTag(TestTags.Capture.SHUTTER).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, h.camera.stops)
        assertEquals(0, h.camera.photos)
    }

    @Test
    fun allowCameraRequestsThePermissionAndThenShowsTheCamera() {
        h.permissions.camera = CameraPermission.ASK
        open()
        assertTrue(compose.exists(TestTags.Capture.ALLOW_CAMERA))
        assertTrue(preview.facings.isEmpty())
        click(TestTags.Capture.ALLOW_CAMERA)
        assertEquals(1, h.permissions.cameraRequests)
        assertFalse(compose.exists(TestTags.Capture.ALLOW_CAMERA))
        assertTrue(compose.exists(TestTags.Capture.SHUTTER))
        assertEquals(CameraFacing.BACK, preview.facings.last())
    }

    @Test
    fun openSettingsShowsThisAppsSettingsPageAndReturningRereadsThePermission() {
        h.permissions.camera = CameraPermission.BLOCKED
        open()
        click(TestTags.Capture.OPEN_SETTINGS)
        val started = shadow.nextStartedActivity ?: shadowOf(compose.activity.application).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(Uri.fromParts("package", context.packageName, null), started.data)
        h.permissions.camera = CameraPermission.GRANTED
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertFalse(compose.exists(TestTags.Capture.OPEN_SETTINGS))
        assertTrue(compose.exists(TestTags.Capture.SHUTTER))
    }

    @Test
    fun closeWithAnEmptyTrayGoesBack() {
        open()
        click(TestTags.Capture.CLOSE)
        assertEquals(listOf(NavCall(RecordingNavigator.POP)), navigator.calls)
    }

    @Test
    fun closeWithNothingToGoBackToOpensHome() {
        val alone = RecordingNavigator(listOf(Route.Capture()))
        open(routes = alone)
        click(TestTags.Capture.CLOSE)
        assertEquals(listOf(NavCall(RecordingNavigator.POP), NavCall(RecordingNavigator.REPLACE_ALL, Route.Home)), alone.calls)
    }

    @Test
    fun discardEmptiesTheTrayAndGoesBack() {
        open()
        tapShutter()
        tapShutter()
        click(TestTags.Capture.CLOSE)
        assertTrue(compose.exists(TestTags.Capture.DISCARD_PANEL))
        click(TestTags.Capture.KEEP)
        assertFalse(compose.exists(TestTags.Capture.DISCARD_PANEL))
        assertEquals(emptyList<NavCall>(), navigator.calls)
        click(TestTags.Capture.CLOSE)
        click(TestTags.Capture.DISCARD)
        assertEquals(0, h.tray.items.size)
        assertEquals(listOf(NavCall(RecordingNavigator.POP)), navigator.calls)
    }

    @Test
    fun anEntryThatCannotTakeMediaIsReplacedByItsViewer() {
        h.entry(mediaCount = 1, isOwn = false)
        open(entryId = ENTRY)
        assertEquals(listOf(NavCall(RecordingNavigator.REPLACE, Route.Entry(ENTRY))), navigator.calls)
    }

    @Test
    fun addToEntryAddsTheItemsAndGoesBackToTheEditPage() {
        h.entry(mediaCount = 1)
        open(entryId = ENTRY, mode = CaptureMode.VIDEO)
        compose.onNodeWithTag(TestTags.Capture.MODE_VIDEO).assertIsSelected()
        click(TestTags.Capture.MODE_PHOTO)
        tapShutter()
        click(TestTags.Capture.ADD_TO_ENTRY)
        assertEquals(listOf(ENTRY to listOf(mediaId("item1"))), h.entries.added)
        assertEquals(listOf(NavCall(RecordingNavigator.POP)), navigator.calls)
    }

    @Test
    fun theWindowStaysOnWhileTheScreenIsShown() {
        open()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun theRefusalBannerNamesARefusedPick() {
        h.imports.refusals["notes.pdf"] = ImportRefusal.UNSUPPORTED
        open()
        click(TestTags.Capture.PICK_FROM_GALLERY)
        val started = nextForResult()
        deliver(started.intent, Activity.RESULT_OK, pickerResult(uri("notes.pdf")))
        compose.onNodeWithTag(TestTags.Capture.REFUSALS).assertExists()
        assertEquals(0, trayCount())
    }

    private companion object {
        const val MAX_CHECKED = 25
    }
}
