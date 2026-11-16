// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.Navigator
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route

@Immutable
internal class ShellContent(
    val navigator: AppNavigator,
    val homeSettings: HomeSettings,
    val content: @Composable (Route) -> Unit,
)

internal val LocalShellContent: ProvidableCompositionLocal<ShellContent?> = staticCompositionLocalOf { null }

internal class RouteScreen(val route: Route) : Screen {
    override val key: ScreenKey = uniqueScreenKey

    @Composable
    override fun Content() {
        LocalShellContent.current?.content?.invoke(route)
    }
}

internal data object HomeScreen : Screen {
    override val key: ScreenKey get() = NavigationConstants.HOME_SCREEN_KEY

    @Composable
    override fun Content() {
        val shell = LocalShellContent.current ?: return
        HomeScaffold(navigator = shell.navigator, settings = shell.homeSettings)
    }
}

@Composable
fun AppNavigation(
    navigator: AppNavigator,
    homeSettings: HomeSettings,
    modifier: Modifier = Modifier,
    content: @Composable (Route) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shell = remember(navigator, homeSettings, content) { ShellContent(navigator, homeSettings, content) }
    CompositionLocalProvider(
        LocalRouteNavigator provides navigator,
        LocalShellContent provides shell,
    ) {
        Navigator(screens = remember(navigator) { navigator.initialScreens() }, onBackPressed = null) { voyager ->
            DisposableEffect(navigator, voyager) {
                navigator.attach(voyager)
                val unbind = navigator.bind(scope)
                onDispose {
                    unbind()
                    navigator.detach(voyager)
                }
            }
            BackHandler(enabled = navigator.canGoBack) { navigator.pop() }
            Box(modifier = modifier) {
                ScreenTransition(navigator = navigator, voyager = voyager)
                if (navigator.securing) content(Route.Securing)
            }
        }
    }
}

@Composable
private fun ScreenTransition(
    navigator: AppNavigator,
    voyager: Navigator,
    modifier: Modifier = Modifier,
) {
    val slideDistance = with(LocalDensity.current) { NavigationConstants.SLIDE_DISTANCE.roundToPx() }
    AnimatedContent(
        targetState = voyager.lastItem,
        transitionSpec = {
            RouteTransitions.contentTransform(
                navigator.lastTransition ?: RouteTransition(TransitionKind.SLIDE, forward = true),
                slideDistance,
            )
        },
        modifier = modifier,
        label = NavigationConstants.TRANSITION_LABEL,
    ) { screen ->
        voyager.saveableState(NavigationConstants.TRANSITION_STATE_KEY, screen) {
            screen.Content()
        }
    }
}
