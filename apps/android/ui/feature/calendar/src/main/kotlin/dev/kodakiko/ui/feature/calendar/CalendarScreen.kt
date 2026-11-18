package dev.kodakiko.ui.feature.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun CalendarScreen(viewModel: CalendarViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(viewModel, navigator) {
        viewModel.events.collect { event ->
            when (event) {
                is CalendarEvent.Open -> navigator.push(event.route)
            }
        }
    }
    CalendarContent(state = state, onAction = viewModel::onAction)
}

internal object CalendarIcons {
    val ChevronLeft: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.calendar_ic_chevron_left)
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.calendar_ic_chevron_right)
    val Today: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.calendar_ic_today)
    val Event: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.calendar_ic_event)
}
