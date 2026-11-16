package dev.kodakiko.ui.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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

fun interface EntryViewerViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryId: String): EntryViewerViewModel
}

fun interface EntryDetailsViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryId: String): EntryDetailsViewModel
}

fun interface VideoPlayerViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryId: String, mediaId: String): VideoPlayerViewModel
}

@Composable
fun EntryViewerScreen(entryId: String) {
    val viewModel = assistedMetroViewModel<EntryViewerViewModel, EntryViewerViewModelFactory>(key = entryId) { create(entryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    val context = LocalContext.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event -> handle(event, navigator, context) }
    }
    LifecycleStartEffect(viewModel) {
        viewModel.onAction(ViewerAction.ScreenVisible(true))
        onStopOrDispose { viewModel.onAction(ViewerAction.ScreenVisible(false)) }
    }
    EntryViewerContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun EntryDetailsScreen(entryId: String) {
    val viewModel = assistedMetroViewModel<EntryDetailsViewModel, EntryDetailsViewModelFactory>(key = entryId) { create(entryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                EntryDetailsEvent.Back -> navigator.pop()
            }
        }
    }
    EntryDetailsContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun VideoPlayerScreen(entryId: String, mediaId: String) {
    val viewModel = assistedMetroViewModel<VideoPlayerViewModel, VideoPlayerViewModelFactory>(key = entryId + mediaId) { create(entryId, mediaId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                VideoPlayerEvent.Back -> navigator.pop()
            }
        }
    }
    VideoPlayerContent(state = state, onAction = viewModel::onAction)
}

private fun handle(event: ViewerEvent, navigator: RouteNavigator, context: Context) {
    when (event) {
        is ViewerEvent.Open -> navigator.push(event.route)
        ViewerEvent.Back -> navigator.pop()
        ViewerEvent.Leave -> if (!navigator.pop()) navigator.replaceAll(Route.Gallery)
        is ViewerEvent.OpenUri -> openUri(context, event.uri)
        is ViewerEvent.Share -> Unit
    }
}

private fun openUri(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
    }
}
