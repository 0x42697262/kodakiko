package dev.kodakiko.ui.feature.links

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

fun interface LinkCreateViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryIds: List<String>): LinkCreateViewModel
}

@Composable
fun LinkCreateScreen(entryIds: List<String>) {
    val key = entryIds.joinToString(LinksConstants.KEY_SEPARATOR)
    LinkCreateScreen(viewModel = assistedMetroViewModel<LinkCreateViewModel, LinkCreateViewModelFactory>(key = key) { create(entryIds) })
}

@Composable
fun LinkCreateScreen(viewModel: LinkCreateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                LinkCreateEvent.Back -> if (!navigator.pop()) navigator.replaceAll(Route.Home)
            }
        }
    }
    LinkCreateContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun LinksListScreen(viewModel: LinksListViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                LinksListEvent.Back -> if (!navigator.pop()) navigator.replaceAll(Route.Settings)
            }
        }
    }
    LinksListContent(state = state, onAction = viewModel::onAction)
}
