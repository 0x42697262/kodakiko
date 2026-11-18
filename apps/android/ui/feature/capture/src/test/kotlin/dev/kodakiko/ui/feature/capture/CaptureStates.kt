package dev.kodakiko.ui.feature.capture

import androidx.compose.ui.graphics.Color
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.ui.core.navigation.CaptureMode

object CaptureStates {
    private val palette = listOf(Color(0xFF8D6E63), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFFFB74D), Color(0xFF81C784))

    fun items(count: Int): List<CaptureTrayItem> = (0 until count).map { CaptureTrayItem(mediaId("tray$it"), solid(palette[it % palette.size])) }

    fun state(
        addToEntry: Boolean = false,
        cameraApp: CameraApp = CameraApp.KODAKIKO,
        permission: CameraPermission = CameraPermission.GRANTED,
        mode: CaptureMode = CaptureMode.PHOTO,
        facing: CameraFacing = CameraFacing.BACK,
        recording: CaptureRecording? = null,
        items: List<CaptureTrayItem> = emptyList(),
        pending: Int = 0,
        refusals: List<CaptureRefusal> = emptyList(),
        dialog: CaptureDialog? = null,
        adding: Boolean = false,
        keepScreenOn: Boolean = true,
    ): CaptureState = CaptureState(
        addToEntry = addToEntry,
        cameraApp = cameraApp,
        permission = permission,
        mode = mode,
        facing = facing,
        recording = recording,
        items = items,
        pending = pending,
        refusals = refusals,
        dialog = dialog,
        adding = adding,
        keepScreenOn = keepScreenOn,
    )

    val idle: CaptureState = state()

    val withTray: CaptureState = state(items = items(3), pending = 2)

    val recording: CaptureState = state(mode = CaptureMode.VIDEO, recording = CaptureRecording(elapsedMs = 63_400), items = items(1))

    val refusals: CaptureState = state(
        items = items(1),
        refusals = listOf(
            CaptureRefusal(ImportRefusal.UNSUPPORTED, RefusalLabel.Named("notes.pdf"), 20),
            CaptureRefusal(ImportRefusal.TOO_LARGE, RefusalLabel.Named("clip.mp4"), 20),
            CaptureRefusal(ImportRefusal.TRAY_FULL, RefusalLabel.ThisPhoto, 20),
        ),
    )
}
