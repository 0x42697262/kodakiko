package dev.kodakiko.ui.feature.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.SettingsItemsPaddings
import dev.kodakiko.ui.core.components.material.DISABLED_ALPHA

@Composable
internal fun ViewerSheets(state: ViewerState, entry: ViewerEntry, onAction: (ViewerAction) -> Unit) {
    when (state.sheet) {
        ViewerSheet.MORE -> MoreSheet(state, entry, onAction)
        ViewerSheet.CONFIRM_DELETE -> ConfirmDeleteSheet(onAction)
        null -> Unit
    }
}

@Composable
private fun MoreSheet(state: ViewerState, entry: ViewerEntry, onAction: (ViewerAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(ViewerAction.DismissSheet) }, modifier = Modifier.testTag(TestTags.Viewer.MORE_SHEET)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = SettingsItemsPaddings.Vertical)) {
            if (state.pinned) {
                SheetRow(ViewerIcons.OfflinePin, stringResource(R.string.viewer_remove_offline), TestTags.Viewer.REMOVE_OFFLINE) {
                    onAction(ViewerAction.ToggleKeepOffline)
                }
            } else {
                SheetRow(ViewerIcons.OfflinePin, stringResource(R.string.viewer_keep_offline), TestTags.Viewer.KEEP_OFFLINE) {
                    onAction(ViewerAction.ToggleKeepOffline)
                }
            }
            if (entry.owner) {
                SheetRow(ViewerIcons.AddPhoto, stringResource(R.string.viewer_add_media), TestTags.Viewer.ADD_MEDIA) { onAction(ViewerAction.AddMedia) }
                SheetRow(ViewerIcons.Delete, stringResource(R.string.viewer_trash), TestTags.Viewer.TRASH) { onAction(ViewerAction.Trash) }
            }
            if (entry.owner && !state.local) {
                SheetRow(ViewerIcons.Share, stringResource(R.string.viewer_share), TestTags.Viewer.SHARE, enabled = state.sharingAvailable) {
                    onAction(ViewerAction.Share)
                }
                if (!state.sharingAvailable) {
                    Text(
                        text = stringResource(R.string.viewer_sharing_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = ViewerDimens.SMALL_SPACING)
                            .testTag(TestTags.Viewer.SHARING_OFF),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = ViewerDimens.CONTROL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(ViewerDimens.SCREEN_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConfirmDeleteSheet(onAction: (ViewerAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(ViewerAction.DismissSheet) }, modifier = Modifier.testTag(TestTags.Viewer.CONFIRM_SHEET)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = ViewerDimens.SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(ViewerDimens.SHEET_PADDING),
        ) {
            Text(
                text = pluralStringResource(R.plurals.bulk_confirm_delete_title, ViewerConstants.SINGLE_ENTRY, ViewerConstants.SINGLE_ENTRY),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ViewerDimens.SMALL_SPACING)) {
                Button(
                    onClick = { onAction(ViewerAction.ConfirmDeletePermanently) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag(TestTags.Bulk.CONFIRM_DELETE),
                ) {
                    Text(text = stringResource(R.string.bulk_confirm_delete))
                }
                TextButton(onClick = { onAction(ViewerAction.DismissSheet) }, modifier = Modifier.testTag(TestTags.Bulk.CANCEL)) {
                    Text(text = stringResource(R.string.bulk_cancel))
                }
            }
        }
    }
}
