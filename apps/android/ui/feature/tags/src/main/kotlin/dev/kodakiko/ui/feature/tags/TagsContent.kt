// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import dev.kodakiko.index.TagCount
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.material.AlertDialog
import dev.kodakiko.ui.core.components.material.DialogButton
import dev.kodakiko.ui.core.components.material.Scaffold
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.AlertDialog as MaterialAlertDialog

private data class TagRowItem(val key: String, val count: TagCount)

private fun rowItems(tags: List<TagCount>): List<TagRowItem> {
    val seen = mutableMapOf<String, Int>()
    return tags.map { count ->
        val occurrence = seen[count.tag] ?: 0
        seen[count.tag] = occurrence + 1
        TagRowItem(count.tag + TagsTextConstants.KEY_SEPARATOR + occurrence, count)
    }
}

@Composable
fun TagsContent(state: TagsState, onAction: (TagsAction) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val lazyListState = rememberLazyListState()
    Scaffold(
        modifier = modifier.testTag(TestTags.Tags.SCREEN),
        topBar = { AppBar(title = stringResource(R.string.tags_title), navigateUp = onBack, navigateUpTestTag = TestTags.Settings.BACK) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(text = stringResource(R.string.tags_add)) },
                icon = { Icon(imageVector = TagsIcons.Add, contentDescription = null) },
                onClick = { onAction(TagsAction.Add) },
                modifier = Modifier.testTag(TestTags.Tags.ADD),
            )
        },
    ) { padding ->
        if (state.tags.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(TagsDimens.LIST_PADDING), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.tags_none_yet),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            TagList(tags = state.tags, lazyListState = lazyListState, padding = padding, onAction = onAction)
        }
    }
    TagsDialogs(dialog = state.dialog, onAction = onAction)
}

@Composable
private fun TagList(tags: List<TagCount>, lazyListState: LazyListState, padding: PaddingValues, onAction: (TagsAction) -> Unit) {
    var rows by remember { mutableStateOf(rowItems(tags)) }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
        onAction(TagsAction.Move(from = from.index, to = to.index))
    }
    LaunchedEffect(tags) {
        if (!reorderableState.isAnyItemDragging) rows = rowItems(tags)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = PaddingValues(
            start = TagsDimens.LIST_PADDING,
            end = TagsDimens.LIST_PADDING,
            top = padding.calculateTopPadding() + TagsDimens.LIST_PADDING,
            bottom = padding.calculateBottomPadding() + TagsDimens.LIST_PADDING,
        ),
        verticalArrangement = Arrangement.spacedBy(TagsDimens.ROW_SPACING),
    ) {
        itemsIndexed(items = rows, key = { _, row -> row.key }) { position, row ->
            ReorderableItem(reorderableState, key = row.key) {
                TagRow(position = position, count = row.count, last = position == rows.lastIndex, onAction = onAction, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.TagRow(position: Int, count: TagCount, last: Boolean, onAction: (TagsAction) -> Unit, modifier: Modifier = Modifier) {
    val moveUp = stringResource(R.string.tags_move_up)
    val moveDown = stringResource(R.string.tags_move_down)
    ElevatedCard(
        modifier = modifier
            .testTag(listItem(TestTags.Tags.ROW, position))
            .semantics {
                customActions = buildList {
                    if (position > 0) {
                        add(
                            CustomAccessibilityAction(moveUp) {
                                onAction(TagsAction.Move(from = position, to = position + TagsConstants.MOVE_UP))
                                true
                            },
                        )
                    }
                    if (!last) {
                        add(
                            CustomAccessibilityAction(moveDown) {
                                onAction(TagsAction.Move(from = position, to = position + TagsConstants.MOVE_DOWN))
                                true
                            },
                        )
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = TagsDimens.ROW_PADDING, top = TagsDimens.ROW_PADDING, bottom = TagsDimens.ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TagsIcons.DragHandle,
                contentDescription = null,
                modifier = Modifier
                    .testTag(listItem(TestTags.Tags.DRAG, position))
                    .draggableHandle()
                    .padding(TagsDimens.HANDLE_PADDING),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = count.tag, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = pluralStringResource(R.plurals.tags_count, count.count, count.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onAction(TagsAction.Rename(count.tag)) }, modifier = Modifier.testTag(listItem(TestTags.Tags.RENAME, position))) {
                Icon(imageVector = TagsIcons.Edit, contentDescription = stringResource(R.string.tags_rename))
            }
            IconButton(onClick = { onAction(TagsAction.Delete(count.tag)) }, modifier = Modifier.testTag(listItem(TestTags.Tags.DELETE, position))) {
                Icon(imageVector = TagsIcons.Delete, contentDescription = stringResource(R.string.tags_delete))
            }
        }
    }
}

@Composable
private fun TagsDialogs(dialog: TagsDialog?, onAction: (TagsAction) -> Unit) {
    when (dialog) {
        is TagsDialog.Add -> NameDialog(title = stringResource(R.string.tags_add), name = dialog.name, invalid = dialog.invalid, onAction = onAction)
        is TagsDialog.Rename -> NameDialog(title = stringResource(R.string.tags_rename), name = dialog.name, invalid = dialog.invalid, onAction = onAction)
        is TagsDialog.Delete -> AlertDialog(
            onDismissRequest = { onAction(TagsAction.Dismiss) },
            confirmButton = DialogButton(
                text = stringResource(R.string.tags_delete),
                onClick = { onAction(TagsAction.ConfirmDelete) },
                testTag = TestTags.Tags.CONFIRM_DELETE,
            ),
            dismissButton = DialogButton(text = stringResource(R.string.settings_cancel), onClick = { onAction(TagsAction.Dismiss) }),
            title = stringResource(R.string.tags_delete),
            text = pluralStringResource(R.plurals.tags_delete_confirm, dialog.count, dialog.tag, dialog.count),
            destructive = true,
        )
        null -> Unit
    }
}

@Composable
private fun NameDialog(title: String, name: String, invalid: Boolean, onAction: (TagsAction) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    MaterialAlertDialog(
        onDismissRequest = { onAction(TagsAction.Dismiss) },
        confirmButton = {
            TextButton(onClick = { onAction(TagsAction.Save) }, modifier = Modifier.testTag(TestTags.Tags.SAVE)) {
                Text(text = stringResource(R.string.tags_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(TagsAction.Dismiss) }) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { onAction(TagsAction.NameChanged(it)) },
                label = { Text(text = stringResource(R.string.tags_name)) },
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(text = stringResource(R.string.tags_invalid)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(TagsAction.Save) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(TestTags.Tags.NAME_FIELD),
            )
        },
    )
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}
