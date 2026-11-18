package dev.kodakiko.ui.core.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.kodakiko.contract.TAGS_PER_ENTRY_MAX
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.SettingsItemsPaddings

fun toggleTagChoice(current: List<String>, tag: String, single: Boolean): List<String> = when {
    single -> if (tag in current) emptyList() else listOf(tag)
    tag in current -> current.filter { it != tag }
    current.size >= TAGS_PER_ENTRY_MAX -> current
    else -> current + tag
}

@Composable
fun ChooseTagsSheet(
    choices: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onEditTags: () -> Unit,
    onDismiss: () -> Unit,
    single: Boolean = false,
) {
    AdaptiveSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(TestTags.Tags.PICKER)) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = TagsSheetDimens.SHEET_PADDING)) {
            Text(
                text = stringResource(R.string.tags_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
            )
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = TagsSheetDimens.SMALL_SPACING)) {
                if (choices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tags_none_to_pick),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = TagsSheetDimens.SMALL_SPACING),
                    )
                }
                choices.forEach { tag ->
                    val checked = tag in selected
                    val enabled = single || checked || selected.size < TAGS_PER_ENTRY_MAX
                    TagOption(tag, checked, enabled, single) { onToggle(tag) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TagsSheetDimens.SMALL_SPACING),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onEditTags, modifier = Modifier.testTag(TestTags.Tags.EDIT_TAGS)) {
                    Text(text = stringResource(R.string.tags_edit_tags))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.Tags.DONE)) {
                    Text(text = stringResource(R.string.tags_done))
                }
            }
        }
    }
}

@Composable
private fun TagOption(tag: String, checked: Boolean, enabled: Boolean, single: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = if (single) Role.RadioButton else Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = SettingsItemsPaddings.Horizontal)
            .testTag(TestTags.Tags.OPTION + TagsSheetConstants.OPTION_SEPARATOR + tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TagsSheetDimens.SMALL_SPACING),
    ) {
        if (single) {
            RadioButton(selected = checked, onClick = null, enabled = enabled)
        } else {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        Text(text = tag, style = MaterialTheme.typography.bodyLarge)
    }
}
