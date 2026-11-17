package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

internal typealias SettingsChange = ((DeviceSettings) -> DeviceSettings) -> Unit

internal suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

@Composable
internal fun SettingsEventsEffect(
    events: Flow<SettingsEvent>,
    fallback: Route = Route.Settings,
    onPickFolder: () -> Unit = {},
    onPickFiles: () -> Unit = {},
) {
    val navigator = LocalRouteNavigator.current
    val pickFolder by rememberUpdatedState(onPickFolder)
    val pickFiles by rememberUpdatedState(onPickFiles)
    LaunchedEffect(events, navigator) {
        events.collect { event ->
            when (event) {
                is SettingsEvent.Open -> navigator.push(event.route)
                is SettingsEvent.Replace -> navigator.replace(event.route)
                SettingsEvent.Back -> if (!navigator.pop()) navigator.replaceAll(fallback)
                SettingsEvent.PickFolder -> pickFolder()
                SettingsEvent.PickFiles -> pickFiles()
            }
        }
    }
}

@Composable
internal fun ConfirmSheet(
    sheetTag: String,
    message: String,
    confirmText: String,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    cancelText: String? = null,
    cancelTag: String? = null,
) {
    AdaptiveSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(sheetTag)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(SettingsDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onConfirm,
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth().testTag(confirmTag),
            ) {
                Text(text = confirmText)
            }
            if (cancelText != null && cancelTag != null) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag(cancelTag)) {
                    Text(text = cancelText)
                }
            }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = SettingsDimens.ITEM_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
