package dev.kodakiko.ui.core.navigation

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

interface RouteNavigator {
    val routes: List<Route>

    fun push(route: Route)

    fun pop(): Boolean

    fun replace(route: Route)

    fun replaceAll(route: Route)
}

val LocalRouteNavigator: ProvidableCompositionLocal<RouteNavigator> =
    staticCompositionLocalOf { error(RouteConstants.NO_NAVIGATOR) }
