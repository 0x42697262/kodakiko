package dev.kodakiko.ui.feature.sharing

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.material.SnackbarMessage
import dev.kodakiko.ui.core.components.material.showMessage
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch

fun interface CollectionViewModelFactory : ManualViewModelAssistedFactory {
    fun create(collectionId: String): CollectionViewModel
}

fun interface ShareWithViewModelFactory : ManualViewModelAssistedFactory {
    fun create(collectionId: String): ShareWithViewModel
}

fun interface ShareSheetViewModelFactory : ManualViewModelAssistedFactory {
    fun create(entryIds: List<String>, inKodakiko: Boolean): ShareSheetViewModel
}

private fun RouteNavigator.back(fallback: Route) {
    if (!pop()) replaceAll(fallback)
}

@Composable
fun SharedScreen(viewModel: SharedViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is SharedEvent.Open -> navigator.push(event.route)
            }
        }
    }
    SharedContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun CollectionScreen(collectionId: String) {
    CollectionScreen(viewModel = assistedMetroViewModel<CollectionViewModel, CollectionViewModelFactory>(key = collectionId) { create(collectionId) })
}

@Composable
fun CollectionScreen(viewModel: CollectionViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    val resources = LocalContext.current.resources
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is CollectionEvent.Open -> navigator.push(event.route)
                CollectionEvent.Back -> navigator.back(Route.Shared)
                is CollectionEvent.Removed -> scope.launch {
                    snackbarHostState.showMessage(SnackbarMessage(resources.getQuantityString(R.plurals.bulk_updated, event.count, event.count), TestTags.Bulk.RESULT))
                }
            }
        }
    }
    BackHandler(enabled = state.selecting) { viewModel.onAction(CollectionAction.Back) }
    CollectionContent(state = state, onAction = viewModel::onAction, snackbarHostState = snackbarHostState)
}

@Composable
fun ShareWithScreen(collectionId: String) {
    ShareWithScreen(viewModel = assistedMetroViewModel<ShareWithViewModel, ShareWithViewModelFactory>(key = collectionId) { create(collectionId) })
}

@Composable
fun ShareWithScreen(viewModel: ShareWithViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                ShareWithEvent.Back -> navigator.back(Route.Shared)
            }
        }
    }
    ShareWithContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun ShareSheetScreen(entryIds: List<String>, inKodakiko: Boolean) {
    val key = entryIds.joinToString(SharingConstants.KEY_SEPARATOR) + inKodakiko
    ShareSheetScreen(viewModel = assistedMetroViewModel<ShareSheetViewModel, ShareSheetViewModelFactory>(key = key) { create(entryIds, inKodakiko) })
}

@Composable
fun ShareSheetScreen(viewModel: ShareSheetViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                ShareSheetEvent.Close -> navigator.back(Route.Home)
                is ShareSheetEvent.Replace -> navigator.replace(event.route)
            }
        }
    }
    AdaptiveSheet(onDismissRequest = { viewModel.onAction(ShareSheetAction.Dismiss) }) {
        ShareSheetContent(state = state, onAction = viewModel::onAction)
    }
}
