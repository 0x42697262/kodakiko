package dev.kodakiko.ui.feature.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.kodakiko.contract.CAPTION_MAX_CHARS
import dev.kodakiko.contract.MediaKind
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.SettingsItemsPaddings
import dev.kodakiko.ui.core.tags.ChooseTagsSheet

@Composable
internal fun EditSheets(state: EditState, body: EditBody, onAction: (EditAction) -> Unit) {
    when (val sheet = state.sheet) {
        is EditSheet.MediaMenu -> MediaMenuSheet(sheet, onAction)
        is EditSheet.Caption -> CaptionSheet(sheet, onAction)
        is EditSheet.Remove -> RemoveSheet(sheet, onAction)
        is EditSheet.ChooseTags -> ChooseTagsSheet(
            choices = sheet.tags,
            selected = body.tags,
            onToggle = { onAction(EditAction.ToggleTag(it)) },
            onEditTags = { onAction(EditAction.EditTags) },
            onDismiss = { onAction(EditAction.DismissSheet) },
        )
        null -> Unit
    }
}

@Composable
private fun MediaMenuSheet(sheet: EditSheet.MediaMenu, onAction: (EditAction) -> Unit) {
    val mediaId = sheet.mediaId
    AdaptiveSheet(onDismissRequest = { onAction(EditAction.DismissSheet) }, modifier = Modifier.testTag(TestTags.Edit.MEDIA_MENU)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = SettingsItemsPaddings.Vertical)) {
            SheetRow(EditIcons.Caption, stringResource(R.string.edit_caption), TestTags.Edit.CAPTION) { onAction(EditAction.EditCaption(mediaId)) }
            if (sheet.canSetCover) {
                SheetRow(EditIcons.Star, stringResource(R.string.edit_set_cover), TestTags.Edit.SET_COVER) { onAction(EditAction.SetCover(mediaId)) }
            }
            SheetRow(EditIcons.Share, stringResource(R.string.edit_share), TestTags.Edit.SHARE_ITEM) { onAction(EditAction.ShareItem(mediaId)) }
            SheetRow(EditIcons.Download, stringResource(R.string.edit_save_to_device), TestTags.Edit.SAVE_TO_DEVICE) { onAction(EditAction.SaveToDevice(mediaId)) }
            if (sheet.kind == MediaKind.PHOTO) {
                SheetRow(EditIcons.Crop, stringResource(R.string.edit_crop), TestTags.Edit.CROP) { onAction(EditAction.Crop(mediaId)) }
            }
            SheetRow(EditIcons.Delete, stringResource(R.string.edit_remove), TestTags.Compose.REMOVE_ITEM) { onAction(EditAction.AskRemove(mediaId)) }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = EditDimens.SHEET_PADDING),
        horizontalArrangement = Arrangement.spacedBy(EditDimens.SCREEN_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = EditDimens.SHEET_PADDING),
        verticalArrangement = Arrangement.spacedBy(EditDimens.SHEET_PADDING),
    ) {
        content()
    }
}

@Composable
private fun CaptionSheet(sheet: EditSheet.Caption, onAction: (EditAction) -> Unit) {
    var text by remember(sheet) { mutableStateOf(sheet.caption) }
    val save = { onAction(EditAction.SaveCaption(sheet.mediaId, text)) }
    AdaptiveSheet(onDismissRequest = { onAction(EditAction.DismissSheet) }, modifier = Modifier.testTag(TestTags.Edit.CAPTION_SHEET)) {
        SheetColumn {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(CAPTION_MAX_CHARS) },
                label = { Text(text = stringResource(R.string.edit_caption)) },
                placeholder = { Text(text = stringResource(R.string.edit_caption_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.Edit.CAPTION_FIELD),
            )
            Button(onClick = save, modifier = Modifier.fillMaxWidth().testTag(TestTags.Edit.CAPTION_SAVE)) {
                Text(text = stringResource(R.string.edit_caption_save))
            }
        }
    }
}

@Composable
private fun kindText(kind: MediaKind): String = stringResource(
    when (kind) {
        MediaKind.PHOTO -> R.string.edit_kind_photo
        MediaKind.VIDEO -> R.string.edit_kind_video
        MediaKind.AUDIO -> R.string.edit_kind_audio
    },
)

@Composable
private fun RemoveSheet(sheet: EditSheet.Remove, onAction: (EditAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(EditAction.DismissSheet) }, modifier = Modifier.testTag(TestTags.Edit.REMOVE_SHEET)) {
        SheetColumn {
            Text(
                text = if (sheet.last) stringResource(R.string.edit_last_item) else stringResource(R.string.edit_remove_title, kindText(sheet.kind)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!sheet.last) {
                Button(
                    onClick = { onAction(EditAction.Remove(sheet.mediaId)) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.Edit.REMOVE_CONFIRM),
                ) {
                    Text(text = stringResource(R.string.edit_remove_confirm))
                }
            }
        }
    }
}
