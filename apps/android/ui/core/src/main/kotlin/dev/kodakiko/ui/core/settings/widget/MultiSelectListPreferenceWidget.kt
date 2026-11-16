// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.settings.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.DialogProperties
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.settings.SettingsConstants
import dev.kodakiko.ui.core.util.optionalTestTag

@Composable
fun <T> MultiSelectListPreferenceWidget(
    values: Set<T>,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    entries: Map<out T, String>,
    onValuesChange: (Set<T>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        MultiSelectListPreferenceDialog(
            title = title,
            values = values,
            entries = entries,
            onValuesChange = onValuesChange,
            onDismissRequest = { isDialogShown = false },
            testTag = testTag,
        )
    }
}

@Composable
fun <T> MultiSelectListPreferenceDialog(
    title: String?,
    values: Set<T>,
    entries: Map<out T, String>,
    onValuesChange: (Set<T>) -> Unit,
    onDismissRequest: () -> Unit,
    message: String? = null,
    testTag: String? = null,
) {
    val selected = remember {
        entries.keys
            .filter { values.contains(it) }
            .toMutableStateList()
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.optionalTestTag(testTag?.let { dialogTag(it) }),
        title = title?.let { { Text(text = it) } },
        text = {
            Column {
                if (message != null) PreferenceDialogMessage(message)
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    entries.forEach { current ->
                        item {
                            val isSelected = selected.contains(current.key)
                            LabeledCheckbox(
                                label = current.value,
                                checked = isSelected,
                                testTag = testTag?.let { optionTag(it, current.key) },
                                onCheckedChange = {
                                    if (it) {
                                        selected.add(current.key)
                                    } else {
                                        selected.remove(current.key)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
        confirmButton = {
            TextButton(
                onClick = {
                    onValuesChange(values.filterNot { it in entries.keys }.toMutableSet().apply { addAll(selected) })
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(R.string.tags_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    testTag: String?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .optionalTestTag(testTag)
            .clip(MaterialTheme.shapes.small)
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.merge(),
            modifier = Modifier.padding(start = SettingsConstants.DIALOG_LABEL_START_PADDING),
        )
    }
}
