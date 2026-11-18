package dev.kodakiko.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.kodakiko.app.ActivityHolder
import dev.kodakiko.data.files.TempFiles
import dev.kodakiko.data.files.TempPurpose
import dev.kodakiko.domain.DeviceLocation
import dev.kodakiko.log.DebugLog
import dev.kodakiko.log.LogEvents
import dev.kodakiko.log.LogFields
import dev.kodakiko.log.LogLevel
import dev.kodakiko.log.LogServices
import dev.kodakiko.platform.camera.CameraFacing
import dev.kodakiko.platform.camera.CameraSession
import dev.kodakiko.platform.camera.PhotoResult
import dev.kodakiko.platform.camera.VideoResult
import dev.kodakiko.platform.location.LocationProvider
import dev.kodakiko.platform.picker.PickerConstants
import dev.kodakiko.platform.picker.UriCopier
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.service.entries.MediaOrigin
import dev.kodakiko.ui.feature.capture.CameraPermission
import dev.kodakiko.ui.feature.capture.CameraPreview
import dev.kodakiko.ui.feature.capture.CameraXSurface
import dev.kodakiko.ui.feature.capture.CaptureCamera
import dev.kodakiko.ui.feature.capture.CaptureClock
import dev.kodakiko.ui.feature.capture.CaptureFiles
import dev.kodakiko.ui.feature.capture.CaptureLocation
import dev.kodakiko.ui.feature.capture.CapturePermissions
import dev.kodakiko.ui.feature.capture.rememberCameraXSurfaceState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

object SystemCaptureClock : CaptureClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

class AppCaptureCamera(private val tempFiles: TempFiles, private val ioDispatcher: CoroutineDispatcher) : CaptureCamera {
    @Volatile
    private var session: CameraSession? = null

    fun attach(session: CameraSession) {
        this.session = session
    }

    fun detach(session: CameraSession) {
        if (this.session === session) this.session = null
    }

    override suspend fun takePhoto(): MediaInput? {
        val camera = session ?: return null
        val output = withContext(ioDispatcher) { emptyTempFile(CaptureAdapterConstants.PHOTO_EXTENSION) }
        return suspendCancellableCoroutine { continuation ->
            camera.takePhoto(output) { result ->
                val input = when (result) {
                    is PhotoResult.Saved -> MediaInput(MediaOrigin.CAMERA, result.file, null, null)
                    is PhotoResult.Failed -> null
                }
                if (continuation.isActive) continuation.resume(input)
            }
        }
    }

    override fun startRecording(maxFileSizeBytes: Long, onFinished: (MediaInput?) -> Unit) {
        val camera = session
        if (camera == null) {
            onFinished(null)
            return
        }
        val output = try {
            emptyTempFile(CaptureAdapterConstants.VIDEO_EXTENSION)
        } catch (_: Exception) {
            onFinished(null)
            return
        }
        camera.startRecording(output, maxFileSizeBytes, withAudio = true) { result ->
            onFinished(
                when (result) {
                    is VideoResult.Saved -> MediaInput(MediaOrigin.CAMERA, result.file, null, null)
                    is VideoResult.Failed -> null
                },
            )
        }
    }

    override fun stopRecording() {
        session?.stopRecording()
    }

    private fun emptyTempFile(extension: String): File {
        val temp = tempFiles.create(TempPurpose.IMPORT, extension)
        temp.close()
        return temp.file
    }
}

@Stable
class AppCameraPreview(private val camera: AppCaptureCamera) : CameraPreview {
    @Composable
    override fun Content(facing: CameraFacing, modifier: Modifier) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val surface = rememberCameraXSurfaceState()
        val session = remember(context, owner) { CameraSession(context, owner) }
        DisposableEffect(session) {
            camera.attach(session)
            onDispose {
                camera.detach(session)
                session.release()
            }
        }
        LaunchedEffect(session, facing) { session.bind(facing, surface.surfaceProvider) {} }
        CameraXSurface(state = surface, modifier = modifier)
    }
}

class ActivityPermissions(private val context: Context, private val holder: ActivityHolder) {
    private val counter = AtomicInteger()

    fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canAskAgain(permission: String): Boolean = holder.current?.shouldShowRequestPermissionRationale(permission) == true

    suspend fun request(permissions: Array<String>): Boolean {
        if (permissions.any(::granted)) return true
        val activity = holder.current ?: return false
        return suspendCancellableCoroutine { continuation ->
            lateinit var launcher: ActivityResultLauncher<Array<String>>
            launcher = activity.activityResultRegistry.register(
                CaptureAdapterConstants.PERMISSION_KEY_PREFIX + counter.incrementAndGet(),
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results ->
                launcher.unregister()
                if (continuation.isActive) continuation.resume(results.values.any { it })
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(permissions)
        }
    }
}

class AppCapturePermissions(private val permissions: ActivityPermissions) : CapturePermissions {
    @Volatile
    private var cameraBlocked = false

    override fun camera(): CameraPermission = when {
        permissions.granted(Manifest.permission.CAMERA) -> CameraPermission.GRANTED
        cameraBlocked && !permissions.canAskAgain(Manifest.permission.CAMERA) -> CameraPermission.BLOCKED
        else -> CameraPermission.ASK
    }

    override suspend fun requestCamera(): CameraPermission {
        if (permissions.request(arrayOf(Manifest.permission.CAMERA))) {
            cameraBlocked = false
            return CameraPermission.GRANTED
        }
        cameraBlocked = !permissions.canAskAgain(Manifest.permission.CAMERA)
        return if (cameraBlocked) CameraPermission.BLOCKED else CameraPermission.ASK
    }

    override fun microphone(): Boolean = permissions.granted(Manifest.permission.RECORD_AUDIO)

    override suspend fun requestMicrophone(): Boolean = permissions.request(arrayOf(Manifest.permission.RECORD_AUDIO))
}

class AppCaptureFiles(
    private val context: Context,
    private val tempFiles: TempFiles,
    private val deviceDeletes: PendingDeviceDeletes,
    private val log: DebugLog,
    private val ioDispatcher: CoroutineDispatcher,
) : CaptureFiles {
    private val copier by lazy { UriCopier(context) }

    override fun phoneCameraOutput(video: Boolean): File {
        val directory = File(context.cacheDir, PickerConstants.CAPTURE_DIRECTORY)
        directory.mkdirs()
        val extension = if (video) CaptureAdapterConstants.VIDEO_EXTENSION else CaptureAdapterConstants.PHOTO_EXTENSION
        return File(directory, CaptureAdapterConstants.PHONE_FILE_PREFIX + UUID.randomUUID() + CaptureAdapterConstants.EXTENSION_SEPARATOR + extension)
    }

    override suspend fun copyPicked(uri: Uri): MediaInput? = withContext(ioDispatcher) {
        val temp = tempFiles.create(TempPurpose.IMPORT, extensionOf(uri))
        temp.close()
        val picked = copier.copy(uri, temp.file) ?: return@withContext null
        rememberOriginal(uri, picked.file)
        MediaInput(MediaOrigin.PICKER, picked.file, picked.displayName, null)
    }

    override fun imported(input: MediaInput, mediaId: String?) {
        deviceDeletes.imported(input.file, mediaId)
    }

    override fun forget(mediaIds: Collection<String>) {
        deviceDeletes.forget(mediaIds)
    }

    private fun rememberOriginal(uri: Uri, file: File) {
        if (!DeviceMediaUris.supported()) return
        val mapped = DeviceMediaUris.mediaStoreUri(context, uri)
        if (mapped != null) {
            deviceDeletes.picked(file, mapped)
        } else {
            log.record(LogLevel.DEBUG, LogServices.IMPORT, LogEvents.DEVICE_ORIGINAL_UNMAPPED, mapOf(LogFields.COUNT to 1))
        }
    }

    private fun extensionOf(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: return CaptureAdapterConstants.PICKED_EXTENSION
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        return if (extension.isNullOrEmpty() || !extension.all(Char::isLetterOrDigit)) CaptureAdapterConstants.PICKED_EXTENSION else extension
    }
}

class AppCaptureLocation(
    private val provider: LocationProvider,
    private val permissions: ActivityPermissions,
    private val ioDispatcher: CoroutineDispatcher,
) : CaptureLocation {
    override suspend fun currentPosition(timeoutMs: Long): DeviceLocation? {
        if (!permissions.request(LOCATION_PERMISSIONS)) return null
        return withContext(ioDispatcher) { provider.currentPosition(timeoutMs) }
    }

    private companion object {
        val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
