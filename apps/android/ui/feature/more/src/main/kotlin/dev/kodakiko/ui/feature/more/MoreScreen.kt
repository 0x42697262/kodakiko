// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.widget.SwitchPreferenceWidget
import dev.kodakiko.ui.core.settings.widget.TextPreferenceWidget
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.Flow

private val TOOL_ROWS = listOf(MoreRow.LOCK_NOW, MoreRow.TAGS, MoreRow.STATISTICS, MoreRow.TRASH, MoreRow.DATA_STORAGE)

private val TAIL_ROWS = listOf(MoreRow.SETTINGS, MoreRow.ABOUT, MoreRow.HELP)

@Composable
fun MoreScreen(viewModel: MoreViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoreEventsEffect(viewModel.events)
    MoreContent(state = state, onAction = viewModel::onAction)
}

@Composable
internal fun MoreEventsEffect(events: Flow<MoreEvent>) {
    val navigator = LocalRouteNavigator.current
    val uriHandler = LocalUriHandler.current
    val helpUrl by rememberUpdatedState(stringResource(R.string.more_help_url))
    LaunchedEffect(events, navigator, uriHandler) {
        events.collect { event ->
            when (event) {
                is MoreEvent.Open -> navigator.push(event.route)
                MoreEvent.OpenHelp -> attempt { uriHandler.openUri(helpUrl) }
                MoreEvent.Back -> if (!navigator.pop()) navigator.replaceAll(Route.More)
            }
        }
    }
}

@Composable
fun MoreContent(state: MoreState, onAction: (MoreAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.testTag(TestTags.More.SCREEN)) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            LogoHeader()
            SwitchPreferenceWidget(
                modifier = Modifier.testTag(TestTags.More.HIDE_SHARED),
                title = stringResource(R.string.more_hide_shared),
                subtitle = stringResource(R.string.more_hide_shared_summary),
                icon = KodakikoIcons.People,
                checked = state.hideShared,
                onCheckedChanged = { onAction(MoreAction.SetHideShared(it)) },
            )
            if (!state.local) {
                SwitchPreferenceWidget(
                    modifier = Modifier.testTag(TestTags.More.PAUSE_SYNC),
                    title = stringResource(R.string.more_pause_sync),
                    subtitle = stringResource(R.string.more_pause_sync_summary),
                    icon = KodakikoIcons.Sync,
                    checked = state.syncPaused,
                    onCheckedChanged = { onAction(MoreAction.SetSyncPaused(it)) },
                )
            }
            HorizontalDivider()
            TOOL_ROWS.forEach { MoreRowItem(it, onAction) }
            HorizontalDivider()
            TAIL_ROWS.forEach { MoreRowItem(it, onAction) }
        }
    }
}

@Composable
private fun LogoHeader() {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.more_app_name),
            modifier = Modifier
                .padding(vertical = MoreDimens.LOGO_PADDING)
                .size(MoreDimens.LOGO_SIZE),
        )
        HorizontalDivider()
    }
}

@Composable
private fun MoreRowItem(row: MoreRow, onAction: (MoreAction) -> Unit) {
    TextPreferenceWidget(
        modifier = Modifier.testTag(row.testTag),
        title = stringResource(row.title),
        icon = row.icon(),
        onPreferenceClick = { onAction(MoreAction.Open(row)) },
    )
}

@Composable
private fun MoreRow.icon(): ImageVector = when (this) {
    MoreRow.LOCK_NOW -> KodakikoIcons.Lock
    MoreRow.TAGS -> KodakikoIcons.Label
    MoreRow.STATISTICS -> KodakikoIcons.BarChart
    MoreRow.TRASH -> KodakikoIcons.Delete
    MoreRow.DATA_STORAGE -> KodakikoIcons.Storage
    MoreRow.SETTINGS -> KodakikoIcons.Settings
    MoreRow.ABOUT -> KodakikoIcons.Info
    MoreRow.HELP -> KodakikoIcons.Help
}
