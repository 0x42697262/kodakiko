package dev.kodakiko.ui.feature.capture

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.ui.core.navigation.CaptureMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private object PaintedPreview : CameraPreview {
    @Composable
    override fun Content(facing: CameraFacing, modifier: Modifier) {
        val colors = if (facing == CameraFacing.BACK) listOf(Color(0xFF546E7A), Color(0xFF263238)) else listOf(Color(0xFF8D6E63), Color(0xFF3E2723))
        Box(modifier = modifier.fillMaxSize().background(Brush.verticalGradient(colors)))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = Screenshots.QUALIFIERS)
class CaptureScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String, state: CaptureState) {
        compose.show(preview = PaintedPreview) { CaptureContent(state = state, onAction = {}) }
        compose.onRoot().captureScreenshot("capture/$name")
    }

    @Test
    fun camera() = capture("camera", CaptureStates.idle)

    @Test
    fun frontCameraVideoMode() = capture("front_video", CaptureStates.state(facing = CameraFacing.FRONT, mode = CaptureMode.VIDEO))

    @Test
    fun trayWithPendingImports() = capture("tray_pending", CaptureStates.withTray)

    @Test
    fun recording() = capture("recording", CaptureStates.recording)

    @Test
    fun refusals() = capture("refusals", CaptureStates.refusals)

    @Test
    fun permissionGate() = capture("permission_ask", CaptureStates.state(permission = CameraPermission.ASK))

    @Test
    fun permissionBlocked() = capture("permission_blocked", CaptureStates.state(permission = CameraPermission.BLOCKED, items = CaptureStates.items(1)))

    @Test
    fun phoneCameraApp() = capture("phone_camera", CaptureStates.state(cameraApp = CameraApp.SYSTEM, items = CaptureStates.items(2)))

    @Test
    fun discardDialog() = capture("discard", CaptureStates.state(items = CaptureStates.items(3), dialog = CaptureDialog.Discard(count = 3)))

    @Test
    fun leftoverDialog() = capture("leftover", CaptureStates.state(addToEntry = true, dialog = CaptureDialog.Leftover(count = 2)))

    @Test
    fun addToEntryWhileAdding() = capture("add_to_entry_adding", CaptureStates.state(addToEntry = true, items = CaptureStates.items(2), adding = true))
}
