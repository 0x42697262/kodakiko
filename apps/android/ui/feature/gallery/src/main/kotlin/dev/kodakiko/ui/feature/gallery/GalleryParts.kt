// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.AppBarActions
import dev.kodakiko.ui.core.components.material.SnackbarAction
import dev.kodakiko.ui.core.components.material.SnackbarMessage
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.components.material.showMessage
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.kodakiko.ui.core.components.AppBar as AppBarAction

@Composable
internal fun GallerySelectionBar(
    count: Int,
    actionsEnabled: Boolean,
    onAction: (GalleryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        titleContent = {
            Text(
                text = stringResource(R.string.gallery_selected, count),
                modifier = Modifier.testTag(TestTags.Gallery.SELECTED),
                maxLines = 1,
            )
        },
        actions = {
            AppBarActions(
                listOf(
                    AppBarAction.Action(
                        title = stringResource(R.string.gallery_select_all),
                        icon = GalleryIcons.SelectAll,
                        testTag = TestTags.Gallery.SELECT_ALL,
                        onClick = { onAction(GalleryAction.SelectAll) },
                    ),
                    AppBarAction.Action(
                        title = stringResource(R.string.gallery_select_inverse),
                        icon = GalleryIcons.FlipToBack,
                        testTag = TestTags.Gallery.SELECT_INVERSE,
                        onClick = { onAction(GalleryAction.InvertSelection) },
                    ),
                    AppBarAction.Action(
                        title = stringResource(R.string.gallery_actions),
                        icon = KodakikoIcons.MoreVert,
                        testTag = TestTags.Gallery.ACTIONS,
                        onClick = { onAction(GalleryAction.OpenActions) },
                        enabled = actionsEnabled,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = { onAction(GalleryAction.ClearSelection) },
        cancelActionModeTestTag = TestTags.Gallery.CANCEL_SELECTION,
    )
}

@Composable
internal fun GallerySnackbarEffect(snackbar: GallerySnackbar?, hostState: SnackbarHostState, onAction: (GalleryAction) -> Unit) {
    val resources = LocalResources.current
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(snackbar, hostState) {
        if (snackbar == null) return@LaunchedEffect
        val message = when (snackbar) {
            GallerySnackbar.Saved -> SnackbarMessage(resources.getString(R.string.entry_saved), TestTags.Entry.SAVED)
            GallerySnackbar.Gone -> SnackbarMessage(resources.getString(R.string.gallery_notice_gone), TestTags.Gallery.NOTICE_GONE)
            is GallerySnackbar.Trashed -> SnackbarMessage(
                text = resources.getString(R.string.gallery_notice_trashed),
                testTag = TestTags.Gallery.NOTICE_TRASHED,
                action = SnackbarAction(
                    label = resources.getString(R.string.gallery_notice_undo),
                    testTag = TestTags.Gallery.NOTICE_UNDO,
                    onClick = { currentOnAction(GalleryAction.UndoTrash) },
                ),
            )
            is GallerySnackbar.BulkDone -> SnackbarMessage(bulkResultText(resources, snackbar), TestTags.Bulk.RESULT)
        }
        if (hostState.showMessage(message) == SnackbarResult.Dismissed) currentOnAction(GalleryAction.SnackbarDismissed)
    }
}

private fun bulkResultText(resources: android.content.res.Resources, done: GallerySnackbar.BulkDone): String {
    val updated = resources.getQuantityString(R.plurals.bulk_updated, done.changed, done.changed)
    if (done.skipped <= 0) return updated
    return updated + GalleryConstants.SENTENCE_SEPARATOR + resources.getString(R.string.bulk_skipped, done.skipped)
}

@Composable
internal fun OpeningIndicator(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(modifier = Modifier.testTag(TestTags.Gallery.OPENING_BUSY))
        Text(
            text = stringResource(R.string.gallery_opening),
            modifier = Modifier.testTag(TestTags.Gallery.OPENING),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun GalleryMessage(
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    action: (@Composable () -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            modifier = Modifier.testTag(testTag).secondaryItemAlpha(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
    if (!fill) {
        Column(
            modifier = modifier.fillMaxWidth().padding(MaterialTheme.padding.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) { content() }
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(MaterialTheme.padding.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium, Alignment.CenterVertically),
        ) { content() }
    }
}

@Composable
internal fun MessageButton(text: String, testTag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag(testTag)) { Text(text) }
}

@Composable
internal fun RemovableChip(
    label: String,
    labelTag: String,
    removeTag: String,
    removeDescription: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier
                    .padding(start = MaterialTheme.padding.small + MaterialTheme.padding.extraSmall)
                    .testTag(labelTag),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            IconButton(onClick = onRemove, modifier = Modifier.testTag(removeTag)) {
                Icon(
                    imageVector = KodakikoIcons.Close,
                    contentDescription = removeDescription,
                    modifier = Modifier.size(GalleryDimens.CHIP_ICON),
                )
            }
        }
    }
}

@Composable
internal fun pluralText(id: Int, count: Int): String = pluralStringResource(id, count, count)
