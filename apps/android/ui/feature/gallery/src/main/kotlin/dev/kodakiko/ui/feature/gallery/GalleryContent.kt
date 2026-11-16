// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.AppBarActions
import dev.kodakiko.ui.core.components.Pill
import dev.kodakiko.ui.core.components.material.KodakikoSnackbarHost
import dev.kodakiko.ui.core.components.material.PullRefresh
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.TabText
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.util.showSoftKeyboard
import kotlinx.coroutines.flow.drop
import dev.kodakiko.ui.core.components.AppBar as AppBarAction

@Composable
fun GalleryContent(state: GalleryState, onAction: (GalleryAction) -> Unit, modifier: Modifier = Modifier) {
    val snackbarHost = remember { SnackbarHostState() }
    GallerySnackbarEffect(state.snackbar, snackbarHost, onAction)
    val selection = state.grid.selection
    Scaffold(
        modifier = modifier.testTag(TestTags.Gallery.SCREEN),
        topBar = {
            Column {
                if (selection.active) {
                    GallerySelectionBar(count = selection.ids.size, actionsEnabled = true, onAction = onAction)
                } else {
                    AnimatedVisibility(visible = !state.barsHidden, enter = expandVertically(), exit = shrinkVertically()) {
                        GalleryToolbar(state, onAction)
                    }
                }
                AnimatedVisibility(visible = !state.barsHidden && state.tagTabs.isNotEmpty(), enter = expandVertically(), exit = shrinkVertically()) {
                    GalleryTagTabs(state.tagTabs, state.showCounts, onAction)
                }
                if (state.chips.isNotEmpty()) FilterChips(state.chips, onAction)
            }
        },
        floatingActionButton = {
            if (!selection.active) {
                FloatingActionButton(
                    onClick = { onAction(GalleryAction.Capture) },
                    modifier = Modifier.testTag(TestTags.Gallery.CAPTURE),
                ) {
                    Icon(imageVector = GalleryIcons.Camera, contentDescription = stringResource(R.string.gallery_capture))
                }
            }
        },
        snackbarHost = { KodakikoSnackbarHost(snackbarHost) },
    ) { padding ->
        PullRefresh(
            refreshing = state.refreshing,
            enabled = state.pullToSync,
            onRefresh = { onAction(GalleryAction.PullToSync) },
            modifier = Modifier.padding(top = padding.calculateTopPadding()).testTag(TestTags.PULL_TO_SYNC),
        ) {
            GalleryBody(state, padding.calculateBottomPadding(), onAction)
        }
    }
    when (val sheet = state.sheet) {
        is GallerySheet.Settings -> GallerySettingsSheet(sheet, onAction)
        is GallerySheet.Actions -> BulkActionsSheet(sheet.bulk, onAction)
        null -> Unit
    }
}

@Composable
private fun GalleryBody(state: GalleryState, bottomPadding: Dp, onAction: (GalleryAction) -> Unit) {
    val inList = state.onThisDay.isNotEmpty()
    when {
        state.opening -> OpeningIndicator()
        state.grid.sections.isEmpty() && !inList && state.empty != null -> GalleryEmptyMessage(state.empty, fill = true, onAction = onAction)
        else -> GalleryGrid(
            grid = state.grid,
            onAction = onAction,
            reportScroll = true,
            bottomPadding = bottomPadding,
            header = if (state.onThisDay.isEmpty()) {
                null
            } else {
                {
                    item(key = GalleryConstants.KEY_ON_THIS_DAY, span = { GridItemSpan(maxLineSpan) }) { OnThisDayCard(state.onThisDay, onAction) }
                }
            },
            footer = state.empty?.takeIf { state.grid.sections.isEmpty() }?.let { empty -> { GalleryEmptyMessage(empty, fill = false, onAction = onAction) } },
        )
    }
}

@Composable
private fun GalleryEmptyMessage(empty: GalleryEmpty, fill: Boolean, onAction: (GalleryAction) -> Unit) {
    when (empty) {
        GalleryEmpty.EMPTY -> GalleryMessage(stringResource(R.string.gallery_empty), TestTags.Gallery.EMPTY, fill = fill)
        GalleryEmpty.NO_MATCHES -> GalleryMessage(stringResource(R.string.gallery_no_matches), TestTags.Gallery.NO_MATCHES, fill = fill) {
            MessageButton(stringResource(R.string.gallery_clear_filters), TestTags.Gallery.CLEAR_FILTERS) { onAction(GalleryAction.ClearFilters) }
        }
    }
}

@Composable
private fun GalleryToolbar(state: GalleryState, onAction: (GalleryAction) -> Unit) {
    val searching = state.searching
    AppBar(
        titleContent = {
            if (searching) {
                SearchField(state.searchText, onAction)
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.gallery_title),
                            modifier = Modifier.testTag(TestTags.Gallery.TITLE),
                            maxLines = 1,
                        )
                        if (state.showCounts) {
                            Pill(
                                text = state.grid.order.size.toString(),
                                modifier = Modifier.testTag(TestTags.Gallery.COUNT),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.home_signed_in_as, state.username),
                        modifier = Modifier.testTag(TestTags.Home.SIGNED_IN_AS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            AppBarActions(
                listOf(
                    AppBarAction.Action(
                        title = stringResource(R.string.gallery_search),
                        icon = if (searching) KodakikoIcons.Close else KodakikoIcons.Search,
                        testTag = TestTags.Gallery.SEARCH,
                        onClick = { onAction(GalleryAction.ToggleSearch) },
                    ),
                    AppBarAction.Action(
                        title = stringResource(if (state.filterActive) R.string.gallery_filter_active else R.string.gallery_filter),
                        icon = GalleryIcons.FilterList,
                        iconTint = if (state.filterActive) MaterialTheme.colorScheme.primary else null,
                        testTag = TestTags.Gallery.FILTER,
                        onClick = { onAction(GalleryAction.OpenSheet) },
                    ),
                    AppBarAction.OverflowAction(
                        title = stringResource(R.string.gallery_random),
                        testTag = TestTags.Gallery.RANDOM,
                        onClick = { onAction(GalleryAction.Random) },
                    ),
                    AppBarAction.OverflowAction(
                        title = stringResource(R.string.lock_lock_now),
                        testTag = TestTags.Lock.LOCK_NOW,
                        onClick = { onAction(GalleryAction.LockNow) },
                    ),
                    AppBarAction.OverflowAction(
                        title = stringResource(R.string.gallery_trash),
                        testTag = TestTags.Gallery.TRASH,
                        onClick = { onAction(GalleryAction.OpenTrash) },
                    ),
                    AppBarAction.OverflowAction(
                        title = stringResource(R.string.home_sign_out),
                        testTag = TestTags.Home.SIGN_OUT,
                        onClick = { onAction(GalleryAction.SignOut) },
                    ),
                ),
                overflowTestTag = TestTags.Gallery.MORE,
            )
        },
    )
}

@Composable
private fun SearchField(text: String, onAction: (GalleryAction) -> Unit) {
    val field = rememberTextFieldState(text)
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }
            .drop(1)
            .collect { currentOnAction(GalleryAction.SearchText(it)) }
    }
    LaunchedEffect(text) {
        if (text != field.text.toString()) field.setTextAndPlaceCursorAtEnd(text)
    }
    BasicTextField(
        state = field,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.Gallery.SEARCH_FIELD)
            .showSoftKeyboard(remember { text.isEmpty() }),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
        decorator = { inner ->
            if (field.text.isEmpty()) {
                Text(
                    text = stringResource(R.string.gallery_search_field),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            inner()
        },
    )
}

@Composable
private fun GalleryTagTabs(tabs: List<TagTab>, showCounts: Boolean, onAction: (GalleryAction) -> Unit) {
    val selected = tabs.indexOfFirst { it.selected }
    PrimaryScrollableTabRow(
        selectedTabIndex = if (selected < 0) 0 else selected,
        edgePadding = 0.dp,
        indicator = {
            if (selected >= 0) {
                TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(selected, matchContentSize = true), width = Dp.Unspecified)
            }
        },
        divider = {},
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab.selected,
                onClick = { onAction(GalleryAction.SelectTab(tab)) },
                modifier = Modifier.testTag(tagOf(tab)),
                text = { TabText(text = labelOf(tab), badgeCount = if (showCounts) tab.count else null) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun labelOf(tab: TagTab): String = when (tab) {
    is TagTab.All -> stringResource(R.string.gallery_tab_all)
    is TagTab.Favorites -> stringResource(R.string.gallery_tab_favorites)
    is TagTab.Tag -> tab.tag
}

private fun tagOf(tab: TagTab): String = when (tab) {
    is TagTab.All -> TestTags.Gallery.TAG_TAB_ALL
    is TagTab.Favorites -> TestTags.Gallery.TAG_TAB_FAVORITES
    is TagTab.Tag -> listItem(TestTags.Gallery.TAG_TAB, tab.position)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(chips: List<FilterChip>, onAction: (GalleryAction) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { position, chip ->
            val label = chipLabel(chip)
            RemovableChip(
                label = label,
                labelTag = listItem(TestTags.Gallery.FILTER_CHIP, position),
                removeTag = listItem(TestTags.Gallery.FILTER_CHIP_REMOVE, position),
                removeDescription = stringResource(R.string.gallery_remove_filter, label),
                onRemove = { onAction(GalleryAction.RemoveChip(chip)) },
            )
        }
        TextButton(onClick = { onAction(GalleryAction.ClearAll) }, modifier = Modifier.testTag(TestTags.Gallery.CLEAR_ALL)) {
            Text(stringResource(R.string.gallery_clear_all))
        }
    }
}

@Composable
private fun chipLabel(chip: FilterChip): String = when (chip) {
    is FilterChip.Tag -> chip.tag
    is FilterChip.Text -> stringResource(R.string.gallery_search_chip, chip.text)
    is FilterChip.Dates -> when {
        chip.from != null && chip.to != null -> stringResource(R.string.gallery_date_range, chip.from, chip.to)
        chip.from != null -> stringResource(R.string.gallery_date_from, chip.from)
        else -> stringResource(R.string.gallery_date_to, chip.to.orEmpty())
    }
}

@Composable
private fun OnThisDayCard(years: List<OnThisDayYear>, onAction: (GalleryAction) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MaterialTheme.padding.small)
            .testTag(TestTags.OnThisDay.CARD),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.padding.medium), verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            Text(
                text = stringResource(R.string.on_this_day_title),
                modifier = Modifier.testTag(TestTags.OnThisDay.TITLE),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GalleryDimens.ON_THIS_DAY_GROUP_SPACING),
            ) {
                var position = 0
                years.forEachIndexed { groupPosition, year ->
                    Column(
                        modifier = Modifier.testTag(listItem(TestTags.OnThisDay.GROUP, groupPosition)),
                        verticalArrangement = Arrangement.spacedBy(GalleryDimens.ON_THIS_DAY_SPACING),
                    ) {
                        Text(
                            text = pluralText(R.plurals.on_this_day_years_ago, year.yearsAgo),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(GalleryDimens.ON_THIS_DAY_SPACING)) {
                            year.entries.forEach { entry ->
                                val tag = listItem(TestTags.OnThisDay.TILE, position)
                                position += 1
                                GalleryThumbnail(
                                    entryId = entry.entryId,
                                    mediaId = entry.coverMediaId,
                                    unreadable = entry.unreadable,
                                    modifier = Modifier
                                        .size(GalleryDimens.ON_THIS_DAY_TILE)
                                        .clip(MaterialTheme.shapes.small)
                                        .testTag(tag)
                                        .clickable(role = Role.Button) { onAction(GalleryAction.OpenEntry(entry.entryId)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
