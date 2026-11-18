// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.media.ThumbnailModeSwitch
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.PreferenceScaffold
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@Immutable
data class SecuritySettingsState(
    val settings: DeviceSettings,
    val biometric: Boolean,
    val modeSwitch: ThumbnailModeSwitch,
    val confirmingThumbnails: Boolean,
)

sealed interface SecuritySettingsAction {
    data class SetAutoLock(val minutes: Int) : SecuritySettingsAction

    data class SetSecureScreen(val on: Boolean) : SecuritySettingsAction

    data class SetHideNotePreviews(val on: Boolean) : SecuritySettingsAction

    data object AskThumbnails : SecuritySettingsAction

    data object DismissThumbnails : SecuritySettingsAction

    data object ConfirmThumbnails : SecuritySettingsAction

    data object Back : SecuritySettingsAction
}

class SecuritySettingsViewModel(deps: SettingsDeps) : DeviceSettingsViewModel(deps, Route.SettingsSecurity) {
    private val biometric = deps.biometric.available()
    private val confirmingThumbnails = MutableStateFlow(false)

    val state: StateFlow<SecuritySettingsState> = combine(deps.settings.settings, deps.thumbnails.modeSwitch, confirmingThumbnails) { settings, modeSwitch, confirming ->
        SecuritySettingsState(settings, biometric, modeSwitch, confirming)
    }.asState(SecuritySettingsState(deps.settings.settings.value, biometric, deps.thumbnails.modeSwitch.value, confirmingThumbnails.value))

    fun onAction(action: SecuritySettingsAction) {
        when (action) {
            is SecuritySettingsAction.SetAutoLock -> update { it.copy(autoLockMinutes = action.minutes) }
            is SecuritySettingsAction.SetSecureScreen -> update { it.copy(secureScreen = action.on) }
            is SecuritySettingsAction.SetHideNotePreviews -> update { it.copy(hideNotePreviews = action.on) }
            SecuritySettingsAction.AskThumbnails -> confirmingThumbnails.value = true
            SecuritySettingsAction.DismissThumbnails -> confirmingThumbnails.value = false
            SecuritySettingsAction.ConfirmThumbnails -> switchThumbnails()
            SecuritySettingsAction.Back -> back()
        }
    }

    private fun switchThumbnails() {
        confirmingThumbnails.value = false
        val encrypt = !deps.settings.settings.value.encryptThumbnails
        update { it.copy(encryptThumbnails = encrypt) }
        viewModelScope.launch { attempt { deps.thumbnails.switchMode(encrypt) } }
    }
}

@Composable
fun SecuritySettingsScreen(viewModel: SecuritySettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    PreferenceScaffold(
        title = stringResource(R.string.prefs_security),
        items = securityItems(state, viewModel::onAction),
        modifier = Modifier.testTag(TestTags.Settings.SECURITY_SCREEN),
        onBackPressed = { viewModel.onAction(SecuritySettingsAction.Back) },
        highlightKey = viewModel.highlightKey,
        navigateUpTestTag = TestTags.Settings.BACK,
    )
    if (state.confirmingThumbnails) {
        ConfirmSheet(
            sheetTag = TestTags.Settings.THUMBNAIL_SHEET,
            message = stringResource(R.string.settings_encrypt_thumbnails_confirm),
            confirmText = stringResource(R.string.settings_confirm),
            confirmTag = TestTags.Settings.THUMBNAIL_CONFIRM,
            onConfirm = { viewModel.onAction(SecuritySettingsAction.ConfirmThumbnails) },
            onDismiss = { viewModel.onAction(SecuritySettingsAction.DismissThumbnails) },
        )
    }
}

@Composable
private fun securityItems(state: SecuritySettingsState, onAction: (SecuritySettingsAction) -> Unit): List<Preference> {
    val settings = state.settings
    val modeSwitch = state.modeSwitch
    val progress = stringResource(R.string.settings_encrypt_thumbnails_progress, modeSwitch.done, modeSwitch.total)
    return listOf(
        Preference.PreferenceItem.ListPreference(
            value = settings.autoLockMinutes,
            entries = SettingsConstants.AUTO_LOCK_MINUTES.associateWith { pluralStringResource(R.plurals.prefs_auto_lock_minutes, it, it) },
            title = stringResource(R.string.settings_auto_lock),
            subtitle = pluralStringResource(R.plurals.settings_auto_lock_background, settings.autoLockMinutes, settings.autoLockMinutes),
            testTag = TestTags.Settings.AUTO_LOCK,
            onValueChanged = { minutes -> onAction(SecuritySettingsAction.SetAutoLock(minutes)) },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.settings_biometric),
            subtitle = stringResource(if (state.biometric) R.string.settings_biometric_on else R.string.settings_biometric_off),
            testTag = TestTags.Settings.BIOMETRIC,
        ),
        Preference.PreferenceItem.SwitchPreference(
            value = settings.secureScreen,
            title = stringResource(R.string.prefs_secure_screen),
            subtitle = stringResource(R.string.prefs_secure_screen_summary),
            testTag = TestTags.Settings.SECURE_SCREEN,
            onValueChanged = { on -> onAction(SecuritySettingsAction.SetSecureScreen(on)) },
        ),
        Preference.PreferenceItem.SwitchPreference(
            value = settings.encryptThumbnails,
            title = stringResource(R.string.settings_encrypt_thumbnails),
            subtitle = stringResource(R.string.settings_encrypt_thumbnails_hint),
            testTag = TestTags.Settings.ENCRYPT_THUMBNAILS,
            enabled = !modeSwitch.running,
            onValueChanged = { onAction(SecuritySettingsAction.AskThumbnails) },
        ),
        Preference.PreferenceItem.CustomPreference(title = "", visible = modeSwitch.running) {
            BodyText(
                text = progress,
                testTag = TestTags.Settings.THUMBNAIL_PROGRESS,
                modifier = Modifier.padding(horizontal = SettingsDimens.SCREEN_PADDING, vertical = SettingsDimens.ITEM_SPACING),
            )
        },
        Preference.PreferenceItem.SwitchPreference(
            value = settings.hideNotePreviews,
            title = stringResource(R.string.prefs_hide_note_previews),
            testTag = TestTags.Settings.HIDE_NOTE_PREVIEWS,
            onValueChanged = { on -> onAction(SecuritySettingsAction.SetHideNotePreviews(on)) },
        ),
    )
}
