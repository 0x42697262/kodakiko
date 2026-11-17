// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.gallery

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.GalleryDisplayMode
import dev.kodakiko.data.db.GalleryGroup
import dev.kodakiko.data.db.GallerySortKey
import dev.kodakiko.index.AttributeKey
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.BaseSortItem
import dev.kodakiko.ui.core.components.CheckboxItem
import dev.kodakiko.ui.core.components.FormHint
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.components.HeadingItem
import dev.kodakiko.ui.core.components.RadioItem
import dev.kodakiko.ui.core.components.SettingsItemsPaddings
import dev.kodakiko.ui.core.components.SliderItem
import dev.kodakiko.ui.core.components.TriState
import dev.kodakiko.ui.core.components.TriStateItem
import dev.kodakiko.ui.core.components.material.AlertDialog
import dev.kodakiko.ui.core.components.material.DialogButton
import dev.kodakiko.ui.core.components.material.TabText
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot
import dev.kodakiko.data.db.TriState as SettingsTriState

private enum class SheetTab(val key: String, val label: Int) {
    FILTER("filter", R.string.sheet_filter),
    SORT("sort", R.string.sheet_sort),
    DISPLAY("display", R.string.sheet_display),
    GROUP("group", R.string.sheet_group),
}

@Composable
internal fun GallerySettingsSheet(sheet: GallerySheet.Settings, onAction: (GalleryAction) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(SheetTab.FILTER) }
    AdaptiveSheet(onDismissRequest = { onAction(GalleryAction.DismissSheet) }) {
        Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.Filter.SHEET)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                SheetTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { TabText(text = stringResource(entry.label)) },
                        modifier = Modifier.testTag(TestTags.Sheet.TAB + GalleryConstants.TAG_PART_SEPARATOR + entry.key),
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = SettingsItemsPaddings.Vertical),
            ) {
                when (tab) {
                    SheetTab.FILTER -> FilterPage(sheet, onAction)
                    SheetTab.SORT -> SortPage(sheet.settings, onAction)
                    SheetTab.DISPLAY -> DisplayPage(sheet.settings, onAction)
                    SheetTab.GROUP -> GroupPage(sheet.group, onAction)
                }
            }
        }
    }
    if (sheet.draft.choosingTags) TagPickerDialog(sheet.tagChoices, sheet.draft.tags, onAction)
}

private fun tag(base: String, key: String): String = base + GalleryConstants.TAG_PART_SEPARATOR + key

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPage(sheet: GallerySheet.Settings, onAction: (GalleryAction) -> Unit) {
    val filters = sheet.settings.galleryFilters
    val rows = listOf(
        Triple(AttributeKey.VIDEO, R.string.sheet_has_video, filters.video),
        Triple(AttributeKey.NOTE, R.string.sheet_has_note, filters.note),
        Triple(AttributeKey.MULTIPLE, R.string.sheet_multiple, filters.multiple),
        Triple(AttributeKey.SHARED, R.string.sheet_shared, filters.shared),
        Triple(AttributeKey.PENDING, R.string.sheet_pending, filters.pending),
        Triple(AttributeKey.FAVORITE, R.string.sheet_favorite, filters.favorite),
    )
    rows.forEach { (key, label, state) ->
        TriStateItem(
            label = stringResource(label),
            state = triState(state),
            onClick = { onAction(GalleryAction.CycleFilter(key)) },
            modifier = Modifier.testTag(tag(TestTags.Sheet.FILTER, key.wire)),
        )
    }
    HeadingItem(stringResource(R.string.sheet_tags_and_dates))
    val draft = sheet.draft
    Column(
        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING),
    ) {
        if (draft.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING)) {
                draft.tags.forEachIndexed { position, name ->
                    RemovableChip(
                        label = name,
                        labelTag = listItem(TestTags.Tags.CHIP, position),
                        removeTag = listItem(TestTags.Tags.REMOVE, position),
                        removeDescription = stringResource(R.string.tags_remove_tag, name),
                        onRemove = { onAction(GalleryAction.DraftTag(name)) },
                    )
                }
            }
        }
        OutlinedButton(onClick = { onAction(GalleryAction.ChooseTags) }, modifier = Modifier.testTag(TestTags.Tags.CHOOSE)) {
            Icon(imageVector = GalleryIcons.Label, contentDescription = null)
            Text(text = stringResource(R.string.tags_choose), modifier = Modifier.padding(start = GalleryDimens.CHIP_SPACING))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING)) {
            FormTextField(
                value = draft.from,
                onValueChange = { onAction(GalleryAction.DraftFrom(it)) },
                label = stringResource(R.string.filter_from),
                testTag = TestTags.Filter.FROM,
                modifier = Modifier.weight(1f),
            )
            FormTextField(
                value = draft.to,
                onValueChange = { onAction(GalleryAction.DraftTo(it)) },
                label = stringResource(R.string.filter_to),
                testTag = TestTags.Filter.TO,
                modifier = Modifier.weight(1f),
            )
        }
        when (draft.problem) {
            DateRangeProblem.START_AFTER_END -> FormHint(stringResource(R.string.filter_start_after_end), testTag = TestTags.Filter.START_AFTER_END)
            DateRangeProblem.INVALID_DATE -> FormHint(stringResource(R.string.filter_invalid_date), testTag = TestTags.Filter.INVALID_DATE)
            null -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING)) {
            Button(onClick = { onAction(GalleryAction.ApplyFilter) }, modifier = Modifier.testTag(TestTags.Filter.APPLY)) {
                Text(stringResource(R.string.filter_apply))
            }
            OutlinedButton(onClick = { onAction(GalleryAction.ResetFilter) }, modifier = Modifier.testTag(TestTags.Filter.RESET)) {
                Text(stringResource(R.string.filter_reset))
            }
        }
    }
}

@Composable
private fun TagPickerDialog(choices: List<String>, picked: List<String>, onAction: (GalleryAction) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onAction(GalleryAction.TagsDone) },
        modifier = Modifier.testTagsAsResourceIdRoot().testTag(TestTags.Tags.PICKER),
        title = { Text(stringResource(R.string.tags_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty()) Text(stringResource(R.string.tags_none_to_pick))
                choices.forEach { name ->
                    CheckboxItem(
                        label = name,
                        checked = name in picked,
                        onClick = { onAction(GalleryAction.DraftTag(name)) },
                        modifier = Modifier.testTag(tag(TestTags.Tags.OPTION, name)),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onAction(GalleryAction.TagsDone) }, modifier = Modifier.testTag(TestTags.Tags.DONE)) {
                Text(stringResource(R.string.tags_done))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { onAction(GalleryAction.EditTags) }, modifier = Modifier.testTag(TestTags.Tags.EDIT_TAGS)) {
                Text(stringResource(R.string.tags_edit_tags))
            }
        },
    )
}

@Composable
private fun SortPage(settings: DeviceSettings, onAction: (GalleryAction) -> Unit) {
    val sort = settings.gallerySort
    val rows = listOf(
        GallerySortKey.TAKEN_AT to R.string.sort_taken_at,
        GallerySortKey.NOTE_LENGTH to R.string.sort_note_length,
        GallerySortKey.MEDIA_COUNT to R.string.sort_media_count,
        GallerySortKey.RANDOM to R.string.sort_random,
    )
    rows.forEach { (key, label) ->
        val icon = when {
            sort.key != key -> null
            key == GallerySortKey.RANDOM -> GalleryIcons.Shuffle
            sort.descending -> KodakikoIcons.ArrowDownward
            else -> KodakikoIcons.ArrowUpward
        }
        BaseSortItem(
            label = stringResource(label),
            icon = icon,
            onClick = { onAction(GalleryAction.Sort(key)) },
            modifier = Modifier.testTag(tag(TestTags.Sheet.SORT, key.wire)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayPage(settings: DeviceSettings, onAction: (GalleryAction) -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    HeadingItem(stringResource(R.string.prefs_display_mode))
    FlowRow(
        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
        horizontalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING),
    ) {
        listOf(
            GalleryDisplayMode.COMPACT_GRID to R.string.prefs_mode_compact,
            GalleryDisplayMode.COMFORTABLE_GRID to R.string.prefs_mode_comfortable,
            GalleryDisplayMode.PHOTO_GRID to R.string.prefs_mode_photo,
            GalleryDisplayMode.LIST to R.string.prefs_mode_list,
        ).forEach { (mode, label) ->
            FilterChip(
                selected = settings.galleryDisplayMode == mode,
                onClick = { onAction(GalleryAction.DisplayMode(mode)) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag(tag(TestTags.Settings.DISPLAY_MODE, mode.wire)),
            )
        }
    }
    if (settings.galleryDisplayMode != GalleryDisplayMode.LIST) {
        val columns = if (landscape) settings.galleryColumnsLandscape else settings.galleryColumnsPortrait
        SliderItem(
            value = columns,
            valueRange = 0..GalleryConstants.COLUMNS_MAX,
            label = stringResource(R.string.prefs_items_per_row),
            onChange = { onAction(GalleryAction.Columns(landscape = landscape, columns = it)) },
            steps = GalleryConstants.COLUMNS_STEPS,
            valueString = if (columns == 0) stringResource(R.string.prefs_columns_auto) else columns.toString(),
            modifier = Modifier.testTag(TestTags.Sheet.COLUMNS),
        )
    }
    HeadingItem(stringResource(R.string.prefs_badges))
    val badges = settings.galleryBadges
    listOf(
        Triple(BadgeKey.FAVORITE, R.string.prefs_badge_favorite, badges.favorite),
        Triple(BadgeKey.VIDEO, R.string.prefs_badge_video, badges.video),
        Triple(BadgeKey.PHOTO_COUNT, R.string.prefs_badge_photo_count, badges.photoCount),
        Triple(BadgeKey.NOTE, R.string.prefs_badge_note, badges.note),
        Triple(BadgeKey.PENDING, R.string.prefs_badge_pending, badges.pending),
    ).forEach { (key, label, on) ->
        CheckboxItem(
            label = stringResource(label),
            checked = on,
            onClick = { onAction(GalleryAction.Badge(key, !on)) },
            modifier = Modifier.testTag(tag(TestTags.Settings.BADGE, badgeWire(key))),
        )
    }
    HeadingItem(stringResource(R.string.prefs_tabs))
    CheckboxItem(
        label = stringResource(R.string.prefs_show_tag_tabs),
        checked = settings.galleryShowTagTabs,
        onClick = { onAction(GalleryAction.ShowTagTabs(!settings.galleryShowTagTabs)) },
        modifier = Modifier.testTag(TestTags.Settings.SHOW_TAG_TABS),
    )
    CheckboxItem(
        label = stringResource(R.string.prefs_show_counts),
        checked = settings.galleryShowCounts,
        onClick = { onAction(GalleryAction.ShowCounts(!settings.galleryShowCounts)) },
        modifier = Modifier.testTag(TestTags.Settings.SHOW_COUNTS),
    )
}

@Composable
private fun GroupPage(group: GalleryGroup, onAction: (GalleryAction) -> Unit) {
    listOf(
        GalleryGroup.DAY to R.string.group_day,
        GalleryGroup.MONTH to R.string.group_month,
        GalleryGroup.YEAR to R.string.group_year,
        GalleryGroup.TAG to R.string.group_tag,
        GalleryGroup.NONE to R.string.group_none,
    ).forEach { (option, label) ->
        RadioItem(
            label = stringResource(label),
            selected = group == option,
            onClick = { onAction(GalleryAction.Group(option)) },
            modifier = Modifier.testTag(tag(TestTags.Sheet.GROUP, option.wire)),
        )
    }
}

private fun badgeWire(key: BadgeKey): String = when (key) {
    BadgeKey.FAVORITE -> GalleryConstants.BADGE_FAVORITE
    BadgeKey.VIDEO -> GalleryConstants.BADGE_VIDEO
    BadgeKey.PHOTO_COUNT -> GalleryConstants.BADGE_PHOTO_COUNT
    BadgeKey.NOTE -> GalleryConstants.BADGE_NOTE
    BadgeKey.PENDING -> GalleryConstants.BADGE_PENDING
}

private fun triState(state: SettingsTriState): TriState = when (state) {
    SettingsTriState.OFF -> TriState.DISABLED
    SettingsTriState.INCLUDE -> TriState.ENABLED_IS
    SettingsTriState.EXCLUDE -> TriState.ENABLED_NOT
}
