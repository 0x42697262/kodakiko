package dev.kodakiko.ui.feature.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun TagsScreen(viewModel: TagsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    TagsContent(
        state = state,
        onAction = viewModel::onAction,
        onBack = { if (!navigator.pop()) navigator.replaceAll(Route.Home) },
    )
}
