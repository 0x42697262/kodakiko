package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator

class SettingsTestHost(private val harness: SettingsHarness, start: List<Route>) : RouteNavigator {
    private val stack = mutableStateListOf<Route>().apply { addAll(start) }
    val pushed = mutableListOf<Route>()
    val replaced = mutableListOf<Route>()
    var pops = 0

    override val routes: List<Route> get() = stack.toList()

    val top: Route get() = stack.last()

    override fun push(route: Route) {
        pushed += route
        stack += route
    }

    override fun pop(): Boolean {
        pops += 1
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    override fun replace(route: Route) {
        replaced += route
        stack[stack.lastIndex] = route
    }

    override fun replaceAll(route: Route) {
        stack.clear()
        stack += route
    }

    @Composable
    fun Content() {
        CompositionLocalProvider(LocalRouteNavigator provides this) {
            val route = stack.last()
            key(stack.size, route) { Screen(route) }
        }
    }

    @Composable
    private fun Screen(route: Route) {
        val deps = harness.deps
        when (route) {
            Route.Settings -> SettingsScreen(viewModel = remember { SettingsViewModel(deps) })
            Route.SettingsSearch -> SettingsSearchScreen(viewModel = remember { SettingsSearchViewModel(deps) })
            Route.SettingsAppearance -> AppearanceSettingsScreen(viewModel = remember { AppearanceSettingsViewModel(deps) })
            Route.SettingsGallery -> GallerySettingsScreen(viewModel = remember { GallerySettingsViewModel(deps) })
            Route.SettingsCapture -> CaptureSettingsScreen(viewModel = remember { CaptureSettingsViewModel(deps) })
            Route.SettingsSync -> SyncSettingsScreen(viewModel = remember { SyncSettingsViewModel(deps) })
            Route.SettingsStorage -> StorageSettingsScreen(viewModel = remember { StorageSettingsViewModel(deps) })
            Route.SettingsSecurity -> SecuritySettingsScreen(viewModel = remember { SecuritySettingsViewModel(deps) })
            Route.SettingsExport -> ExportScreen(viewModel = remember { ExportViewModel(deps) })
            Route.SettingsImport -> ImportScreen(viewModel = remember { ImportViewModel(deps) })
            else -> Box(Modifier.fillMaxSize().testTag(otherTag(route)))
        }
    }

    companion object {
        fun otherTag(route: Route): String = "other:" + route.path
    }
}
