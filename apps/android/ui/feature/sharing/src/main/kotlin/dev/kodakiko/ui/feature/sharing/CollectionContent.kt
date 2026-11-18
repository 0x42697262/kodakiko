@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.sharing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.js.jsTrim
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.material.KodakikoSnackbarHost
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.format.collectionNameText
import dev.kodakiko.ui.core.icons.KodakikoIcons

@Composable
fun CollectionContent(
    state: CollectionState,
    onAction: (CollectionAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val journal = state.journal
    Scaffold(
        modifier = modifier.testTag(TestTags.Collection.SCREEN),
        topBar = {
            AppBar(
                titleContent = { if (journal != null) CollectionTitle(journal) },
                navigateUp = { onAction(CollectionAction.Back) },
                navigateUpTestTag = TestTags.Shared.BACK,
                actions = { if (journal != null) CollectionActions(state, onAction) },
                scrollBehavior = it,
            )
        },
        snackbarHost = { KodakikoSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (journal != null) {
            if (state.tiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.gallery_empty),
                    modifier = Modifier.padding(padding).padding(SharingDimens.SCREEN_PADDING).testTag(TestTags.Gallery.EMPTY),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TileGrid(state, onAction, padding)
            }
        }
    }
    if (journal != null) CollectionSheets(state.sheet, journal, onAction)
}

@Composable
private fun CollectionTitle(journal: SharedJournal) {
    Column {
        Text(
            text = collectionNameText(LocalContext.current.resources, journal.name),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(TestTags.Collection.TITLE),
        )
        if (journal.role != CollectionRole.OWNER) {
            Text(
                text = stringResource(R.string.shared_shared_by, journal.ownerUsername),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.Collection.SUBTITLE),
            )
        }
    }
}

@Composable
private fun CollectionActions(state: CollectionState, onAction: (CollectionAction) -> Unit) {
    if (state.selecting) {
        TextButton(
            onClick = { onAction(CollectionAction.OpenActions) },
            enabled = state.selected.isNotEmpty(),
            modifier = Modifier.testTag(TestTags.Gallery.ACTIONS),
        ) {
            Text(text = stringResource(R.string.gallery_actions))
        }
    } else {
        IconButton(onClick = { onAction(CollectionAction.More) }, modifier = Modifier.testTag(TestTags.Collection.MORE)) {
            Icon(imageVector = KodakikoIcons.MoreVert, contentDescription = stringResource(R.string.shared_more))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileGrid(state: CollectionState, onAction: (CollectionAction) -> Unit, padding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(SharingConstants.GRID_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(SharingDimens.GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(SharingDimens.GRID_SPACING),
    ) {
        itemsIndexed(state.tiles, key = { _, tile -> tile.entryId }) { position, tile ->
            val selected = tile.entryId in state.selected
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .testTag(listItem(TestTags.Gallery.TILE, position))
                    .combinedClickable(
                        onClick = { onAction(CollectionAction.TileClick(tile.entryId)) },
                        onLongClick = { onAction(CollectionAction.TileLongClick(tile.entryId)) },
                    ),
            ) {
                Thumbnail(
                    entryId = tile.entryId,
                    mediaId = tile.coverMediaId,
                    modifier = Modifier.fillMaxSize().alpha(if (state.selecting && !selected) SharingConstants.UNSELECTED_ALPHA else 1f),
                )
                if (selected) {
                    Icon(
                        imageVector = SharingIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(SharingDimens.CHECK_PADDING).size(SharingDimens.CHECK_SIZE),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionSheets(sheet: CollectionSheet?, journal: SharedJournal, onAction: (CollectionAction) -> Unit) {
    if (sheet == null) return
    val name = collectionNameText(LocalContext.current.resources, journal.name)
    AdaptiveSheet(onDismissRequest = { onAction(CollectionAction.Dismiss) }, modifier = Modifier.testTag(TestTags.Collection.SHEET)) {
        SheetColumn {
            when (sheet) {
                CollectionSheet.Menu -> CollectionMenu(journal, onAction)
                is CollectionSheet.Rename -> RenamePanel(sheet, onAction)
                is CollectionSheet.ConfirmDelete -> ConfirmPanel(
                    text = stringResource(R.string.shared_delete_confirm, name),
                    action = stringResource(R.string.shared_delete_action),
                    testTag = TestTags.Collection.DELETE_CONFIRM,
                    busy = sheet.busy,
                    error = sheet.error,
                    onConfirm = { onAction(CollectionAction.ConfirmDelete) },
                )
                is CollectionSheet.ConfirmLeave -> ConfirmPanel(
                    text = stringResource(R.string.shared_leave_confirm, name, journal.ownerUsername),
                    action = stringResource(R.string.shared_leave_action),
                    testTag = TestTags.Collection.LEAVE_CONFIRM,
                    busy = sheet.busy,
                    error = sheet.error,
                    onConfirm = { onAction(CollectionAction.ConfirmLeave) },
                )
                is CollectionSheet.Actions -> {
                    SharingError(sheet.error)
                    ActionRow(
                        icon = null,
                        label = stringResource(R.string.bulk_remove_from_journal),
                        testTag = TestTags.Bulk.REMOVE_FROM_JOURNAL,
                        enabled = !sheet.busy,
                    ) { onAction(CollectionAction.RemoveFromJournal) }
                }
            }
        }
    }
}

@Composable
private fun CollectionMenu(journal: SharedJournal, onAction: (CollectionAction) -> Unit) {
    if (journal.role == CollectionRole.OWNER) {
        ActionRow(KodakikoIcons.People, stringResource(R.string.shared_share_with), TestTags.Collection.SHARE_WITH, enabled = true) {
            onAction(CollectionAction.ShareWith)
        }
        ActionRow(null, stringResource(R.string.shared_rename), TestTags.Collection.RENAME, enabled = true) { onAction(CollectionAction.Rename) }
        ActionRow(null, stringResource(R.string.shared_delete), TestTags.Collection.DELETE, enabled = true, color = MaterialTheme.colorScheme.error) {
            onAction(CollectionAction.Delete)
        }
    } else {
        ActionRow(null, stringResource(R.string.shared_leave), TestTags.Collection.LEAVE, enabled = true, color = MaterialTheme.colorScheme.error) {
            onAction(CollectionAction.Leave)
        }
    }
}

@Composable
private fun RenamePanel(sheet: CollectionSheet.Rename, onAction: (CollectionAction) -> Unit) {
    NameField(name = sheet.name, onChange = { onAction(CollectionAction.NameChanged(it)) }, onDone = { onAction(CollectionAction.Save) })
    SharingError(sheet.error)
    FormButton(
        text = stringResource(R.string.shared_save),
        onClick = { onAction(CollectionAction.Save) },
        testTag = TestTags.Shared.SAVE,
        enabled = jsTrim(sheet.name).isNotEmpty(),
        busy = sheet.busy,
    )
}

@Composable
internal fun ConfirmPanel(text: String, action: String, testTag: String, busy: Boolean, error: SharingMessage?, onConfirm: () -> Unit) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
    SharingError(error)
    DangerButton(text = action, onClick = onConfirm, testTag = testTag, busy = busy)
}
