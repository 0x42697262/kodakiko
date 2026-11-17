package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.SettingsSearchContent
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events, fallback = Route.Home)
    SettingsContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun SettingsSearchScreen(viewModel: SettingsSearchViewModel = metroViewModel()) {
    val resources = LocalContext.current.resources
    val sections = remember(resources) { settingsSearchSections(resources) }
    SettingsEventsEffect(viewModel.events)
    SettingsSearchContent(
        sections = sections,
        onResultClick = { viewModel.onAction(SettingsSearchAction.Open(it)) },
        navigateUp = { viewModel.onAction(SettingsSearchAction.Back) },
    )
}
