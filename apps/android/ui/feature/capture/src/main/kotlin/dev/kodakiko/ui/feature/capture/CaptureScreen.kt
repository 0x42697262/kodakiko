package dev.kodakiko.ui.feature.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.platform.picker.MediaPicking
import dev.kodakiko.platform.picker.PhoneCamera
import dev.kodakiko.platform.picker.PickerRequest
import dev.kodakiko.ui.core.navigation.CaptureMode
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

fun interface CaptureViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryId: String?, mode: CaptureMode): CaptureViewModel
}

private class PendingPicker {
    var request: PickerRequest<PickVisualMediaRequest, *>? = null
}

private class PendingCamera {
    var request: CaptureEvent.OpenPhoneCamera? = null
}

private typealias ResultLauncher = ManagedActivityResultLauncher<Intent, ActivityResult>

@Composable
fun CaptureScreen(entryId: String?, mode: CaptureMode) {
    val viewModel = assistedMetroViewModel<CaptureViewModel, CaptureViewModelFactory>(key = entryId.orEmpty() + mode.name) { create(entryId, mode) }
    CaptureScreen(viewModel = viewModel)
}

@Composable
fun CaptureScreen(viewModel: CaptureViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    val context = LocalContext.current
    val pending = remember { PendingPicker() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending.request
        pending.request = null
        val parsed = request?.contract?.parseResult(result.resultCode, result.data)
        viewModel.onAction(CaptureAction.Picked(pickedUris(parsed)))
    }
    val pendingCamera = remember { PendingCamera() }
    val phoneCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        revokeCameraGrant(context, pendingCamera)
        viewModel.onAction(CaptureAction.PhoneCameraResult(ok = result.resultCode == Activity.RESULT_OK))
    }
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                CaptureEvent.Back -> if (!navigator.pop()) navigator.replaceAll(Route.Home)
                is CaptureEvent.Open -> navigator.push(event.route)
                is CaptureEvent.Replace -> navigator.replace(event.route)
                CaptureEvent.OpenAppSettings -> openAppSettings(context)
                is CaptureEvent.PickFromGallery -> pickFromGallery(context, picker, pending, event.remainingSlots, viewModel)
                is CaptureEvent.OpenPhoneCamera -> openPhoneCamera(context, phoneCamera, pendingCamera, event, viewModel)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(CaptureAction.Resumed)
        onPauseOrDispose {}
    }
    CaptureContent(state = state, onAction = viewModel::onAction)
}

private fun pickedUris(result: Any?): List<Uri> = when (result) {
    is Uri -> listOf(result)
    is List<*> -> result.filterIsInstance<Uri>()
    else -> emptyList()
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts(CaptureConstants.PACKAGE_SCHEME, context.packageName, null))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private fun pickFromGallery(context: Context, launcher: ResultLauncher, pending: PendingPicker, remainingSlots: Int, viewModel: CaptureViewModel) {
    val request = MediaPicking.request(remainingSlots) ?: return
    pending.request = request
    try {
        launcher.launch(request.contract.createIntent(context, request.input))
    } catch (_: ActivityNotFoundException) {
        pending.request = null
        viewModel.onAction(CaptureAction.Picked(emptyList()))
    }
}

private fun revokeCameraGrant(context: Context, pending: PendingCamera) {
    val request = pending.request ?: return
    pending.request = null
    try {
        PhoneCamera.revokeGrant(context, request.authority, request.output)
    } catch (_: IllegalArgumentException) {
    }
}

private fun openPhoneCamera(context: Context, launcher: ResultLauncher, pending: PendingCamera, event: CaptureEvent.OpenPhoneCamera, viewModel: CaptureViewModel) {
    revokeCameraGrant(context, pending)
    pending.request = event
    try {
        val intent = if (event.video) {
            PhoneCamera.videoIntent(context, event.authority, event.output)
        } else {
            PhoneCamera.photoIntent(context, event.authority, event.output)
        }
        launcher.launch(intent)
    } catch (_: ActivityNotFoundException) {
        revokeCameraGrant(context, pending)
        viewModel.onAction(CaptureAction.PhoneCameraResult(ok = false))
    } catch (_: IllegalArgumentException) {
        revokeCameraGrant(context, pending)
        viewModel.onAction(CaptureAction.PhoneCameraResult(ok = false))
    }
}
