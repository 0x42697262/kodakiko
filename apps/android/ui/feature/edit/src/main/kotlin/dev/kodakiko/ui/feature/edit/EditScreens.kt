package dev.kodakiko.ui.feature.edit

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
fun EditScreen(entryId: String, isNew: Boolean) {
    val viewModel = assistedMetroViewModel<EditViewModel, EditViewModelFactory>(key = entryId + isNew) { create(entryId, isNew) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onAction(EditAction.ToggleRecording)
    }
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event -> handle(event, navigator, context) }
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
    }
}

private fun openUri(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
    }
}
