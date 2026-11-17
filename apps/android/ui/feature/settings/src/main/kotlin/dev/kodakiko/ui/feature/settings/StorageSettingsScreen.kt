// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.PreferenceScaffold
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun StorageSettingsScreen(viewModel: StorageSettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    StorageSettingsContent(state = state, onAction = viewModel::onAction, update = viewModel::update, highlightKey = viewModel.highlightKey)
}

@Composable
private fun StorageSettingsContent(
    state: StorageSettingsState,
    onAction: (StorageSettingsAction) -> Unit,
    update: SettingsChange,
    highlightKey: String?,
) {
    PreferenceScaffold(
        title = stringResource(R.string.prefs_data_storage),
        items = storageItems(state, onAction, update),
        modifier = Modifier.testTag(TestTags.Settings.STORAGE_SCREEN),
        onBackPressed = { onAction(StorageSettingsAction.Back) },
        highlightKey = highlightKey,
        navigateUpTestTag = TestTags.Settings.BACK,
    )
    if (state.confirmingClear) {
        ConfirmSheet(
            sheetTag = TestTags.Settings.CLEAR_SHEET,
            message = stringResource(R.string.settings_clear_originals_confirm),
            confirmText = stringResource(R.string.settings_clear_action),
            confirmTag = TestTags.Settings.CLEAR_CONFIRM,
            onConfirm = { onAction(StorageSettingsAction.ConfirmClear) },
            onDismiss = { onAction(StorageSettingsAction.DismissClear) },
            danger = true,
        )
    }
}

@Composable
private fun storageItems(
    state: StorageSettingsState,
    onAction: (StorageSettingsAction) -> Unit,
    update: SettingsChange,
): List<Preference> {
    val settings = state.settings
    val server = !state.local
    val noLimit = stringResource(R.string.settings_no_limit)
    return listOf(
        Preference.PreferenceItem.CustomPreference(title = "") { UsageSection(state.usage, state.device) },
        Preference.PreferenceItem.InfoPreference(
            title = stringResource(R.string.storage_location) + SettingsConstants.LABEL_SEPARATOR + stringResource(R.string.storage_location_android),
            testTag = TestTags.Storage.LOCATION,
        ),
        Preference.PreferenceGroup(
            title = stringResource(R.string.storage_backup),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.storage_create_backup),
                    subtitle = stringResource(R.string.prefs_export_summary),
                    icon = KodakikoIcons.Upload,
                    testTag = TestTags.Settings.ROW_EXPORT,
                    onClick = { onAction(StorageSettingsAction.Export) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.storage_restore_backup),
                    subtitle = stringResource(R.string.storage_restore_summary),
                    icon = KodakikoIcons.Download,
                    testTag = TestTags.Settings.ROW_IMPORT,
                    onClick = { onAction(StorageSettingsAction.Import) },
                ),
            ),
        ),
        Preference.PreferenceGroup(
            title = stringResource(R.string.storage_cache),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.autoplayVideos,
                    title = stringResource(R.string.prefs_autoplay),
                    subtitle = stringResource(R.string.prefs_autoplay_summary),
                    testTag = TestTags.Settings.AUTOPLAY,
                    onValueChanged = { on -> update { it.copy(autoplayVideos = on) } },
                ),
                Preference.PreferenceItem.ListPreference(
                    value = settings.originalCacheCapBytes,
                    entries = SettingsConstants.ORIGINAL_CACHE_CAPS.associateWith { if (it == SettingsConstants.NO_LIMIT) noLimit else formatBytes(it) },
                    title = stringResource(R.string.settings_cache_cap),
                    testTag = TestTags.Settings.CACHE_CAP,
                    visible = server,
                    onValueChanged = { bytes -> onAction(StorageSettingsAction.SetCacheCap(bytes)) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.settings_clear_originals),
                    subtitle = state.usage?.let { stringResource(R.string.storage_used, formatBytes(it.originalsBytes)) },
                    testTag = TestTags.Settings.CLEAR_ORIGINALS,
                    visible = server,
                    onClick = { onAction(StorageSettingsAction.Clear) },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.largeUploadsOnWifiOnly,
                    title = stringResource(R.string.settings_wifi_only),
                    testTag = TestTags.Settings.WIFI_ONLY,
                    visible = server,
                    onValueChanged = { on -> update { it.copy(largeUploadsOnWifiOnly = on) } },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    value = settings.autoplayOnMobileData,
                    title = stringResource(R.string.prefs_autoplay_mobile),
                    subtitle = stringResource(R.string.prefs_autoplay_mobile_summary),
                    testTag = TestTags.Settings.AUTOPLAY_MOBILE,
                    enabled = settings.autoplayVideos,
                    visible = server,
                    onValueChanged = { on -> update { it.copy(autoplayOnMobileData = on) } },
                ),
                Preference.PreferenceItem.ListPreference(
                    value = settings.largeUploadThresholdBytes,
                    entries = SettingsConstants.LARGE_UPLOAD_THRESHOLDS.associateWith { formatBytes(it) },
                    title = stringResource(R.string.settings_large_means),
                    testTag = TestTags.Settings.LARGE_THRESHOLD,
                    visible = server,
                    onValueChanged = { bytes -> update { it.copy(largeUploadThresholdBytes = bytes) } },
                ),
            ),
        ),
    )
}

@Composable
private fun UsageSection(usage: CacheUsage?, device: DeviceStorage?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsDimens.SCREEN_PADDING)
            .padding(top = SettingsDimens.USAGE_TOP_PADDING),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.ITEM_SPACING),
    ) {
        Text(text = stringResource(R.string.storage_usage), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        if (device != null) {
            LinearProgressIndicator(
                progress = { usedShare(device) },
                modifier = Modifier.fillMaxWidth().height(SettingsDimens.USAGE_BAR_HEIGHT),
            )
            Text(
                text = stringResource(R.string.storage_available, formatBytes(device.freeBytes), formatBytes(device.totalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.Storage.DEVICE),
            )
        }
        if (usage != null) {
            UsageFigure(stringResource(R.string.settings_usage_originals), usage.originalsBytes, TestTags.Settings.USAGE_ORIGINALS)
            UsageFigure(stringResource(R.string.settings_usage_thumbnails), usage.thumbnailsBytes, TestTags.Settings.USAGE_THUMBNAILS)
            UsageFigure(stringResource(R.string.settings_usage_pinned), usage.pinnedBytes, TestTags.Settings.USAGE_PINNED)
            UsageFigure(stringResource(R.string.settings_usage_pending), usage.pendingUploadBytes, TestTags.Settings.USAGE_PENDING)
        }
    }
}

private fun usedShare(device: DeviceStorage): Float {
    if (device.totalBytes <= 0L) return 0f
    return ((device.totalBytes - device.freeBytes).toFloat() / device.totalBytes).coerceIn(0f, SettingsConstants.FULL_PROGRESS)
}

@Composable
private fun UsageFigure(label: String, bytes: Long, testTag: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = formatBytes(bytes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(testTag))
    }
}
