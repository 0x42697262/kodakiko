// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.app.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.lifecycle.ScreenLifecycleStore
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.util.systemWindowInsets

internal data class RouteTab(val route: Route.Tab) : Tab {
    override val key: ScreenKey get() = route.path

    override val options: TabOptions
        @Composable get() = TabOptions(index = Route.tabs.indexOf(route).toUShort(), title = stringResource(route.label()))

    @Composable
    override fun Content() {
        LocalShellContent.current?.content?.invoke(route)
    }
}

internal fun disposeTabs() {
    Route.tabs.forEach { ScreenLifecycleStore.remove(RouteTab(it)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScaffold(navigator: AppNavigator, settings: HomeSettings) {
    val selected = navigator.currentTab
    val wanted = remember(selected) { RouteTab(selected) }
    DisposableEffect(navigator) {
        onDispose {
            if (!navigator.homeInStack()) disposeTabs()
        }
    }
    TabNavigator(tab = wanted, key = NavigationConstants.TAB_NAVIGATOR_KEY) { tabNavigator ->
        SideEffect {
            if (tabNavigator.current != wanted) tabNavigator.current = wanted
        }
        var barVisible by remember(selected) { mutableStateOf(true) }
        val connection = remember(settings.hideBarsOnScroll) {
            HideOnScrollConnection(enabled = settings.hideBarsOnScroll) { barVisible = it }
        }
        val tabs = Route.tabs.filter { it != Route.Shared || settings.sharedTabVisible }
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = barVisible || !settings.hideBarsOnScroll,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    NavigationBar(
                        windowInsets = systemWindowInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ) {
                        tabs.forEach { tab ->
                            TabItem(tab = tab, selected = tab == selected, onClick = { navigator.selectTab(tab) })
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
                    .nestedScroll(connection),
            ) {
                AnimatedContent(
                    targetState = wanted,
                    transitionSpec = {
                        fadeIn(tween(NavigationConstants.TAB_FADE_DURATION_MS)) togetherWith
                            fadeOut(tween(NavigationConstants.TAB_FADE_DURATION_MS))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = NavigationConstants.TAB_CONTENT_LABEL,
                ) { tab ->
                    tabNavigator.saveableState(key = NavigationConstants.TAB_STATE_KEY, tab) {
                        tab.Content()
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Route.Tab, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = tab.icon(), contentDescription = null) },
        label = { Text(text = stringResource(tab.label())) },
        modifier = Modifier.testTag(tab.testTag()),
    )
}

private class HideOnScrollConnection(
    private val enabled: Boolean,
    private val onVisibleChange: (Boolean) -> Unit,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (enabled) {
            if (available.y < 0f) onVisibleChange(false)
            if (available.y > 0f) onVisibleChange(true)
        }
        return Offset.Zero
    }
}

private fun Route.Tab.label(): Int = when (this) {
    Route.Gallery -> R.string.tabs_gallery
    Route.Calendar -> R.string.tabs_calendar
    Route.Shared -> R.string.tabs_shared
    Route.More -> R.string.tabs_more
}

private fun Route.Tab.testTag(): String = when (this) {
    Route.Gallery -> TestTags.Tabs.GALLERY
    Route.Calendar -> TestTags.Tabs.CALENDAR
    Route.Shared -> TestTags.Tabs.SHARED
    Route.More -> TestTags.Tabs.MORE
}

@Composable
private fun Route.Tab.icon(): ImageVector = when (this) {
    Route.Gallery -> KodakikoIcons.PhotoLibrary
    Route.Calendar -> KodakikoIcons.CalendarMonth
    Route.Shared -> KodakikoIcons.People
    Route.More -> KodakikoIcons.MoreHoriz
}
