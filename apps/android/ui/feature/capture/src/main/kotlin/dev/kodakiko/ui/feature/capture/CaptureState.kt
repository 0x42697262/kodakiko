package dev.kodakiko.ui.feature.capture

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.kodakiko.data.db.CameraApp
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.Route
import java.io.File

@Immutable
data class CaptureRecording(val elapsedMs: Long)

@Immutable
data class CaptureTrayItem(val mediaId: String, val thumbnail: ImageBitmap?)

@Immutable
sealed interface RefusalLabel {
    data class Named(val name: String) : RefusalLabel

    data object ThisPhoto : RefusalLabel

    data object ThisVideo : RefusalLabel
}

@Immutable
data class CaptureRefusal(val reason: ImportRefusal, val label: RefusalLabel, val max: Int)

@Immutable
sealed interface CaptureDialog {
    data class Discard(val count: Int) : CaptureDialog

    data class Leftover(val count: Int) : CaptureDialog
}

data class CaptureActionsEnabled(val next: Boolean, val addToEntry: Boolean)

@Immutable
data class CaptureState(
    val addToEntry: Boolean,
    val cameraApp: CameraApp,
    val permission: CameraPermission,
    val mode: CaptureMode,
    val facing: CameraFacing,
    val recording: CaptureRecording?,
    val items: List<CaptureTrayItem>,
    val pending: Int,
    val refusals: List<CaptureRefusal>,
    val dialog: CaptureDialog?,
    val adding: Boolean,
    val keepScreenOn: Boolean,
) {
    private val enabled: CaptureActionsEnabled get() = captureActionsEnabled(items.size, pending, recording != null)

    val canNext: Boolean get() = enabled.next

    val canAddToEntry: Boolean get() = enabled.addToEntry
}

sealed interface CaptureAction {
    data object ShutterDown : CaptureAction

    data object ShutterUp : CaptureAction

    data object AllowCamera : CaptureAction

    data object OpenSettings : CaptureAction

    data object Resumed : CaptureAction

    data object Flip : CaptureAction

    data class SetMode(val mode: CaptureMode) : CaptureAction

    data object PickFromGallery : CaptureAction

    data class Picked(val uris: List<Uri>) : CaptureAction

    data class Remove(val mediaId: String) : CaptureAction

    data class PhoneCamera(val video: Boolean) : CaptureAction

    data class PhoneCameraResult(val ok: Boolean) : CaptureAction

    data object Next : CaptureAction

    data object AddToEntry : CaptureAction

    data object Close : CaptureAction

    data object Keep : CaptureAction

    data object Discard : CaptureAction

    data object CancelAdd : CaptureAction
}

sealed interface CaptureEvent {
    data object Back : CaptureEvent

    data object OpenAppSettings : CaptureEvent

    data class Open(val route: Route) : CaptureEvent

    data class Replace(val route: Route) : CaptureEvent

    data class PickFromGallery(val remainingSlots: Int) : CaptureEvent

    data class OpenPhoneCamera(val video: Boolean, val output: File, val authority: String) : CaptureEvent
}
