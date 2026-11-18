// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.material.KodakikoSnackbarHost
import dev.kodakiko.ui.core.components.material.PullRefresh
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.format.formatDayHeader
import dev.kodakiko.ui.core.icons.KodakikoIcons

@Composable
fun TrashContent(state: TrashState, onAction: (GalleryAction) -> Unit, modifier: Modifier = Modifier) {
    SecondaryGridScaffold(
        screenTag = TestTags.Trash.SCREEN,
        title = stringResource(R.string.trash_title),
        titleTag = TestTags.Trash.TITLE,
        backTag = TestTags.Trash.BACK,
        grid = state.grid,
        opening = state.opening,
        pullToSync = state.pullToSync,
        refreshing = state.refreshing,
        sheet = state.sheet,
        snackbar = state.snackbar,
        onAction = onAction,
        modifier = modifier,
        banner = {
            Banner(
                text = stringResource(R.string.trash_retention, state.retentionDays),
                kind = BannerKind.INFO,
                testTag = TestTags.Trash.RETENTION,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
            )
        },
        empty = { GalleryMessage(stringResource(R.string.trash_empty), TestTags.Trash.EMPTY) },
    )
}

@Composable
fun DayContent(state: DayState, onAction: (GalleryAction) -> Unit, modifier: Modifier = Modifier) {
    SecondaryGridScaffold(
        screenTag = TestTags.Day.SCREEN,
        title = formatDayHeader(LocalResources.current, state.date, state.grid.today),
        titleTag = TestTags.Day.TITLE,
        backTag = TestTags.Day.BACK,
        grid = state.grid,
        opening = state.opening,
        pullToSync = state.pullToSync,
        refreshing = state.refreshing,
        sheet = state.sheet,
        snackbar = state.snackbar,
        onAction = onAction,
        modifier = modifier,
        empty = {
            GalleryMessage(stringResource(R.string.day_empty), TestTags.Day.EMPTY) {
                OutlinedButton(onClick = { onAction(GalleryAction.Back) }, modifier = Modifier.testTag(TestTags.Day.BACK)) {
                    Icon(imageVector = KodakikoIcons.ArrowBack, contentDescription = null)
                    Text(text = stringResource(R.string.day_back), modifier = Modifier.padding(start = GalleryDimens.CHIP_SPACING))
                }
            }
        },
    )
}

@Composable
private fun SecondaryGridScaffold(
    screenTag: String,
    title: String,
    titleTag: String,
    backTag: String,
    grid: GalleryGrid,
    opening: Boolean,
    pullToSync: Boolean,
    refreshing: Boolean,
    sheet: GallerySheet?,
    snackbar: GallerySnackbar?,
    onAction: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
    banner: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    val snackbarHost = remember { SnackbarHostState() }
    GallerySnackbarEffect(snackbar, snackbarHost, onAction)
    val selection = grid.selection
    Scaffold(
        modifier = modifier.testTag(screenTag),
        topBar = {
            if (selection.active) {
                GallerySelectionBar(count = selection.ids.size, actionsEnabled = selection.ids.isNotEmpty(), onAction = onAction)
            } else {
                AppBar(
                    titleContent = { Text(text = title, modifier = Modifier.testTag(titleTag), maxLines = 1) },
                    navigateUp = { onAction(GalleryAction.Back) },
                    navigateUpTestTag = backTag,
                )
            }
        },
        snackbarHost = { KodakikoSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            banner?.invoke()
            PullRefresh(
                refreshing = refreshing,
                enabled = pullToSync,
                onRefresh = { onAction(GalleryAction.PullToSync) },
                modifier = Modifier.testTag(TestTags.PULL_TO_SYNC),
            ) {
                when {
                    opening -> OpeningIndicator()
                    grid.sections.isEmpty() -> empty()
                    else -> GalleryGrid(grid = grid, onAction = onAction)
                }
            }
        }
    }
    if (sheet is GallerySheet.Actions) BulkActionsSheet(sheet.bulk, onAction)
}
