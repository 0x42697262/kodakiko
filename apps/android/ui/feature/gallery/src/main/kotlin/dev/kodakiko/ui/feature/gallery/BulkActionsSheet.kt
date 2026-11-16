// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.gallery

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.SettingsItemsPaddings
import dev.kodakiko.ui.core.components.material.DISABLED_ALPHA
import dev.kodakiko.ui.core.components.material.padding

@Composable
internal fun BulkActionsSheet(bulk: BulkSheet, onAction: (GalleryAction) -> Unit) {
    AdaptiveSheet(
        onDismissRequest = { onAction(GalleryAction.DismissSheet) },
        modifier = Modifier.testTag(TestTags.Gallery.ACTIONS_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = SettingsItemsPaddings.Vertical),
        ) {
            if (bulk.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when (bulk.panel) {
                BulkPanel.MENU -> BulkMenu(bulk, onAction)
                BulkPanel.ADD_TAG, BulkPanel.REMOVE_TAG -> BulkTagPanel(bulk, onAction)
                BulkPanel.CONFIRM_DELETE -> BulkDeletePanel(bulk, onAction)
            }
        }
    }
}

@Composable
private fun BulkMenu(bulk: BulkSheet, onAction: (GalleryAction) -> Unit) {
    Column(modifier = Modifier.testTag(TestTags.Bulk.MENU)) {
        if (bulk.failed) {
            Banner(
                text = stringResource(R.string.errors_generic),
                kind = BannerKind.ERROR,
                testTag = TestTags.Bulk.RESULT,
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
            )
        }
        val enabled = !bulk.busy
        when (bulk.mode) {
            BulkMode.JOURNAL -> {
                BulkRow(GalleryIcons.Label, stringResource(R.string.bulk_add_tag), TestTags.Bulk.ADD_TAG, enabled) { onAction(GalleryAction.BulkAddTag) }
                BulkRow(GalleryIcons.LabelOff, stringResource(R.string.bulk_remove_tag), TestTags.Bulk.REMOVE_TAG, enabled) { onAction(GalleryAction.BulkRemoveTag) }
                BulkRow(GalleryIcons.Favorite, stringResource(R.string.bulk_favorite), TestTags.Bulk.FAVORITE, enabled) { onAction(GalleryAction.BulkFavorite(true)) }
                BulkRow(GalleryIcons.FavoriteBorder, stringResource(R.string.bulk_unfavorite), TestTags.Bulk.UNFAVORITE, enabled) {
                    onAction(GalleryAction.BulkFavorite(false))
                }
                BulkRow(GalleryIcons.Delete, stringResource(R.string.bulk_trash), TestTags.Bulk.TRASH, enabled) { onAction(GalleryAction.BulkTrash) }
                BulkRow(GalleryIcons.Share, stringResource(R.string.bulk_share), TestTags.Bulk.SHARE, enabled) { onAction(GalleryAction.BulkShare) }
            }
            BulkMode.TRASH -> {
                BulkRow(GalleryIcons.Restore, stringResource(R.string.bulk_restore), TestTags.Bulk.RESTORE, enabled) { onAction(GalleryAction.BulkRestore) }
                BulkRow(
                    GalleryIcons.DeleteForever,
                    stringResource(R.string.bulk_delete_permanently),
                    TestTags.Bulk.DELETE_PERMANENTLY,
                    enabled,
                    MaterialTheme.colorScheme.error,
                ) { onAction(GalleryAction.BulkDeletePermanently) }
            }
        }
    }
}

@Composable
private fun BulkRow(
    icon: ImageVector,
    label: String,
    testTag: String,
    enabled: Boolean,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkTagPanel(bulk: BulkSheet, onAction: (GalleryAction) -> Unit) {
    val adding = bulk.panel == BulkPanel.ADD_TAG
    Column(
        modifier = Modifier
            .testTag(TestTags.Bulk.TAG_PANEL)
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(if (adding) R.string.bulk_add_tag else R.string.bulk_remove_tag),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(GalleryDimens.CHIP_SPACING)) {
            bulk.tagChoices.forEach { name ->
                FilterChip(
                    selected = bulk.pickedTag == name,
                    onClick = { onAction(GalleryAction.BulkPickTag(name)) },
                    label = { Text(name) },
                    modifier = Modifier.testTag(TestTags.Bulk.TAG_OPTION + GalleryConstants.TAG_PART_SEPARATOR + name),
                )
            }
        }
        if (bulk.tagChoices.isEmpty()) {
            Text(
                text = stringResource(R.string.tags_none_to_pick),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            Button(
                onClick = { onAction(GalleryAction.BulkConfirmTag) },
                enabled = bulk.pickedTag != null && !bulk.busy,
                modifier = Modifier.testTag(if (adding) TestTags.Bulk.CONFIRM_ADD else TestTags.Bulk.CONFIRM_REMOVE),
            ) {
                Text(stringResource(if (adding) R.string.bulk_confirm_add else R.string.bulk_confirm_remove))
            }
            TextButton(onClick = { onAction(GalleryAction.BulkCancel) }, modifier = Modifier.testTag(TestTags.Bulk.CANCEL)) {
                Text(stringResource(R.string.bulk_cancel))
            }
        }
    }
}

@Composable
private fun BulkDeletePanel(bulk: BulkSheet, onAction: (GalleryAction) -> Unit) {
    Column(
        modifier = Modifier
            .testTag(TestTags.Bulk.DELETE_PANEL)
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = pluralText(R.plurals.bulk_confirm_delete_title, bulk.count),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            Button(
                onClick = { onAction(GalleryAction.BulkConfirmDelete) },
                enabled = !bulk.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.testTag(TestTags.Bulk.CONFIRM_DELETE),
            ) {
                Text(stringResource(R.string.bulk_confirm_delete))
            }
            TextButton(onClick = { onAction(GalleryAction.BulkCancel) }, modifier = Modifier.testTag(TestTags.Bulk.CANCEL)) {
                Text(stringResource(R.string.bulk_cancel))
            }
        }
    }
}
