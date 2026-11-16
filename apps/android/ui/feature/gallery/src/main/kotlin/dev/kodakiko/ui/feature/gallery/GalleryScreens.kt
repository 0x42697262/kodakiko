package dev.kodakiko.ui.feature.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.RouteNavigator
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.Flow

fun interface DayViewModelFactory : ManualViewModelAssistedFactory {
    fun create(date: String): DayViewModel
}

@Composable
fun GalleryScreen(viewModel: GalleryViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GalleryEvents(viewModel.events, viewModel::onAction)
    GalleryContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun TrashScreen(viewModel: TrashViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GalleryEvents(viewModel.events, viewModel::onAction)
    TrashContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun DayScreen(date: String) {
    val viewModel = assistedMetroViewModel<DayViewModel, DayViewModelFactory>(key = date) { create(date) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    GalleryEvents(viewModel.events, viewModel::onAction)
    DayContent(state = state, onAction = viewModel::onAction)
}

@Composable
private fun GalleryEvents(events: Flow<GalleryEvent>, onAction: (GalleryAction) -> Unit) {
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(events, navigator) {
        events.collect { event -> handle(event, navigator) }
    }
    val currentOnAction by rememberUpdatedState(onAction)
    DisposableEffect(Unit) {
        onDispose { currentOnAction(GalleryAction.ScreenLeft) }
    }
}

private fun handle(event: GalleryEvent, navigator: RouteNavigator) {
    when (event) {
        is GalleryEvent.Open -> navigator.push(event.route)
        GalleryEvent.Back -> navigator.pop()
        is GalleryEvent.Share -> Unit
    }
}
