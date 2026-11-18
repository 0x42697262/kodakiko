package dev.kodakiko.ui.feature.edit

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.platform.picker.MediaPicking
import dev.kodakiko.platform.picker.PickerRequest
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

fun interface EditViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryId: String, isNew: Boolean): EditViewModel
}

fun interface CropViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryId: String, mediaId: String): CropViewModel
}

private class PendingPick {
    var request: PickerRequest<PickVisualMediaRequest, *>? = null
}

private typealias PickLauncher = ManagedActivityResultLauncher<Intent, ActivityResult>

@Composable
fun EditScreen(entryId: String, isNew: Boolean) {
    val viewModel = assistedMetroViewModel<EditViewModel, EditViewModelFactory>(key = entryId + isNew) { create(entryId, isNew) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onAction(EditAction.ToggleRecording)
    }
    val pending = remember { PendingPick() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending.request
        pending.request = null
        viewModel.onAction(EditAction.Picked(pickedUris(request?.contract?.parseResult(result.resultCode, result.data))))
    }
    BackHandler { viewModel.onAction(EditAction.Back) }
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            if (event is EditEvent.PickMedia) pick(context, picker, pending, event.remainingSlots) else handle(event, navigator, context)
        }
    }
    LifecycleStartEffect(viewModel) {
        viewModel.onAction(EditAction.ScreenVisible(true))
        onStopOrDispose { viewModel.onAction(EditAction.ScreenVisible(false)) }
    }
    EditContent(
        state = state,
        onAction = { action ->
            val needsMicrophone = action == EditAction.ToggleRecording && state.recording == null && !microphoneGranted(context)
            if (needsMicrophone) microphone.launch(Manifest.permission.RECORD_AUDIO) else viewModel.onAction(action)
        },
    )
}

@Composable
fun CropScreen(entryId: String, mediaId: String) {
    val viewModel = assistedMetroViewModel<CropViewModel, CropViewModelFactory>(key = entryId + mediaId) { create(entryId, mediaId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                CropEvent.Back, CropEvent.Done -> navigator.pop()
            }
        }
    }
    CropContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun EntryNewScreen(viewModel: EntryNewViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is EntryNewEvent.Replace -> navigator.replace(event.route)
            }
        }
    }
    EntryNewContent(state = state)
}

private fun pickedUris(result: Any?): List<Uri> = when (result) {
    is Uri -> listOf(result)
    is List<*> -> result.filterIsInstance<Uri>()
    else -> emptyList()
}

private fun pick(context: Context, launcher: PickLauncher, pending: PendingPick, remainingSlots: Int) {
    val request = MediaPicking.request(remainingSlots) ?: return
    pending.request = request
    try {
        launcher.launch(request.contract.createIntent(context, request.input))
    } catch (_: ActivityNotFoundException) {
        pending.request = null
    }
}

private fun microphoneGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun handle(event: EditEvent, navigator: RouteNavigator, context: Context) {
    when (event) {
        EditEvent.Back -> if (!navigator.pop()) navigator.replaceAll(Route.Home)
        EditEvent.Home -> navigator.replaceAll(Route.Home)
        is EditEvent.Open -> navigator.push(event.route)
        is EditEvent.Replace -> navigator.replace(event.route)
        is EditEvent.OpenUri -> openUri(context, event.uri)
        is EditEvent.Share -> navigator.push(Route.ShareSheet(event.entryIds, event.inKodakiko))
        is EditEvent.PickMedia -> Unit
    }
}

private fun openUri(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
    }
}
