package dev.kodakiko.ui.feature.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.platform.device.HapticKind
import dev.kodakiko.service.account.ConfigService
import dev.kodakiko.service.entries.CaptureTray
import dev.kodakiko.service.entries.EntriesService
import dev.kodakiko.service.entries.ImportPipeline
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.index.IndexService
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class CameraPermission { GRANTED, ASK, BLOCKED }

interface CaptureCamera {
    suspend fun takePhoto(): MediaInput?

    fun startRecording(maxFileSizeBytes: Long, onFinished: (MediaInput?) -> Unit)

    fun stopRecording()
}

interface CapturePermissions {
    fun camera(): CameraPermission

    suspend fun requestCamera(): CameraPermission

    fun microphone(): Boolean

    suspend fun requestMicrophone(): Boolean
}

interface CaptureFiles {
    fun phoneCameraOutput(video: Boolean): File

    suspend fun copyPicked(uri: Uri): MediaInput?

    fun imported(input: MediaInput, mediaId: String?) {}

    fun forget(mediaIds: Collection<String>) {}
}

fun interface CaptureLocation {
    suspend fun currentPosition(timeoutMs: Long): DeviceLocation?
}

fun interface CaptureClock {
    fun nowMs(): Long
}

interface CameraPreview {
    @Composable
    fun Content(facing: CameraFacing, modifier: Modifier)
}

object NoCameraPreview : CameraPreview {
    @Composable
    override fun Content(facing: CameraFacing, modifier: Modifier) {
        Box(modifier = modifier.background(Color.Black))
    }
}

val LocalCameraPreview: ProvidableCompositionLocal<CameraPreview> = staticCompositionLocalOf { NoCameraPreview }

class CaptureDeps(
    val tray: CaptureTray,
    val imports: ImportPipeline,
    val entries: EntriesService,
    val index: IndexService,
    val config: ConfigService,
    val settings: StateFlow<DeviceSettings>,
    val camera: CaptureCamera,
    val permissions: CapturePermissions,
    val files: CaptureFiles,
    val location: CaptureLocation,
    val haptics: (HapticKind) -> Unit,
    val clock: CaptureClock,
    val fileProviderAuthority: String,
)
