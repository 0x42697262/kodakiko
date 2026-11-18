// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.util.optionalTestTag
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun AccountSettingsScreen(viewModel: AccountSettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    AccountSettingsContent(state = state, onAction = viewModel::onAction, highlightKey = viewModel.highlightKey)
}

@Composable
fun AccountSettingsContent(state: AccountSettingsState, onAction: (AccountSettingsAction) -> Unit, highlightKey: String? = null) {
    PinnedPreferencePage(
        title = stringResource(R.string.settings_account_title),
        items = accountItems(state, onAction),
        screenTag = TestTags.Settings.ACCOUNT_SCREEN,
        onBack = { onAction(AccountSettingsAction.Back) },
        backTag = TestTags.Settings.BACK,
        highlightKey = highlightKey,
    )
    if (state.changingServer) {
        ConfirmSheet(
            sheetTag = TestTags.Settings.CHANGE_SERVER_SHEET,
            message = stringResource(R.string.settings_change_server_title),
            confirmText = stringResource(R.string.settings_change_server_action),
            confirmTag = TestTags.Settings.CHANGE_SERVER_CONFIRM,
            onConfirm = { onAction(AccountSettingsAction.ConfirmChangeServer) },
            onDismiss = { onAction(AccountSettingsAction.DismissChangeServer) },
            danger = true,
            cancelText = stringResource(R.string.settings_cancel),
            cancelTag = TestTags.Settings.CANCEL,
            warning = if (state.unsynced > 0) pluralStringResource(R.plurals.settings_change_server_unsynced, state.unsynced, state.unsynced) else null,
        )
    }
    state.recovery?.let { RecoverySheetContent(it, onAction) }
}

@Composable
private fun accountItems(state: AccountSettingsState, onAction: (AccountSettingsAction) -> Unit): List<Preference> {
    val server = !state.local
    val storageText = state.storage?.let { storage ->
        val use = stringResource(R.string.settings_storage_use, formatBytes(storage.usedBytes), formatBytes(storage.quotaBytes))
        storage.asOf?.let { use + SettingsConstants.NOTE_OPEN + stringResource(R.string.settings_as_of, it) + SettingsConstants.NOTE_CLOSE } ?: use
    }
    return listOf(
        Preference.PreferenceItem.CustomPreference(title = stringResource(R.string.settings_username)) {
            LabeledValue(stringResource(R.string.settings_username), state.username, TestTags.Settings.USERNAME)
        },
        Preference.PreferenceItem.CustomPreference(title = "", visible = state.local) {
            BodyText(text = stringResource(R.string.settings_local_only), testTag = TestTags.Settings.LOCAL_ONLY, modifier = Modifier.padding(SettingsDimens.ITEM_PADDING))
        },
        Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.settings_connect),
            icon = KodakikoIcons.Cloud,
            testTag = TestTags.Settings.ROW_CONNECT,
            visible = state.local,
            onClick = { onAction(AccountSettingsAction.Open(Route.SettingsConnect)) },
        ),
        Preference.PreferenceItem.CustomPreference(title = stringResource(R.string.settings_server), visible = server) {
            LabeledValue(stringResource(R.string.settings_server), state.host, TestTags.Settings.SERVER)
        },
        Preference.PreferenceItem.CustomPreference(title = "", visible = server && storageText != null) {
            Text(
                text = storageText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(SettingsDimens.ITEM_PADDING).testTag(TestTags.Settings.STORAGE_USE),
            )
        },
        Preference.PreferenceItem.CustomPreference(title = "", visible = server) {
            FormButton(
                text = stringResource(R.string.settings_change_server),
                onClick = { onAction(AccountSettingsAction.AskChangeServer) },
                testTag = TestTags.Settings.CHANGE_SERVER,
                style = FormButtonStyle.SECONDARY,
                modifier = Modifier.padding(SettingsDimens.ITEM_PADDING),
            )
        },
        Preference.PreferenceItem.CustomPreference(title = stringResource(R.string.settings_security_code), visible = server) {
            SecurityCode(state.securityCode)
        },
        Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.server_title),
            icon = KodakikoIcons.Dns,
            testTag = TestTags.Settings.ROW_SERVER,
            onClick = { onAction(AccountSettingsAction.Open(Route.SettingsServer)) },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.settings_change_password),
            icon = KodakikoIcons.Key,
            testTag = TestTags.Settings.ROW_PASSWORD,
            onClick = { onAction(AccountSettingsAction.Open(Route.SettingsPassword)) },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(R.string.settings_show_recovery),
            icon = KodakikoIcons.Key,
            testTag = TestTags.Settings.ROW_RECOVERY,
            onClick = { onAction(AccountSettingsAction.ShowRecovery) },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(if (state.local) R.string.settings_erase else R.string.settings_delete_account),
            icon = KodakikoIcons.DeleteForever,
            testTag = TestTags.Settings.ROW_DELETE_ACCOUNT,
            danger = true,
            onClick = { onAction(AccountSettingsAction.Open(Route.SettingsDeleteAccount)) },
        ),
    )
}

@Composable
internal fun LabeledValue(label: String, value: String, testTag: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(SettingsDimens.ITEM_PADDING), verticalArrangement = Arrangement.spacedBy(SettingsDimens.VALUE_SPACING)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.secondaryItemAlpha())
        SelectionContainer {
            Text(text = value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.optionalTestTag(testTag))
        }
    }
}

@Composable
private fun SecurityCode(code: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(SettingsDimens.ITEM_PADDING), verticalArrangement = Arrangement.spacedBy(SettingsDimens.ROW_SPACING)) {
        Text(text = stringResource(R.string.settings_security_code), style = MaterialTheme.typography.bodySmall, modifier = Modifier.secondaryItemAlpha())
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(TestTags.Settings.SECURITY_CODE),
            )
        }
        Text(text = stringResource(R.string.settings_security_code_others), style = MaterialTheme.typography.bodySmall, modifier = Modifier.secondaryItemAlpha())
    }
}

@Composable
private fun RecoverySheetContent(sheet: RecoverySheet, onAction: (AccountSettingsAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(AccountSettingsAction.CloseRecovery) }, modifier = Modifier.testTag(TestTags.Settings.RECOVERY_SHEET)) {
        when (sheet) {
            is RecoverySheet.Prompt -> Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(SettingsDimens.SCREEN_PADDING),
                verticalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
            ) {
                RecoveryPrompt(sheet, onAction)
            }
            is RecoverySheet.Words -> Column(
                modifier = Modifier.fillMaxWidth().padding(SettingsDimens.SCREEN_PADDING),
                verticalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
            ) {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    RecoveryWords(sheet.words)
                }
                FormButton(
                    text = stringResource(R.string.settings_recovery_done),
                    onClick = { onAction(AccountSettingsAction.CloseRecovery) },
                    testTag = TestTags.Settings.RECOVERY_DONE,
                )
            }
        }
    }
}

@Composable
private fun RecoveryPrompt(prompt: RecoverySheet.Prompt, onAction: (AccountSettingsAction) -> Unit) {
    BodyText(text = stringResource(R.string.settings_recovery_prompt))
    FormTextField(
        value = prompt.password,
        onValueChange = { onAction(AccountSettingsAction.SetRecoveryPassword(it)) },
        label = stringResource(R.string.settings_delete_password),
        testTag = TestTags.Settings.RECOVERY_PASSWORD,
        password = true,
        imeAction = ImeAction.Done,
        onImeAction = { onAction(AccountSettingsAction.RevealRecovery) },
    )
    if (prompt.wrong) Banner(text = stringResource(R.string.settings_password_wrong), kind = BannerKind.ERROR, testTag = TestTags.Settings.ERROR)
    FormButton(
        text = stringResource(R.string.settings_recovery_show),
        onClick = { onAction(AccountSettingsAction.RevealRecovery) },
        testTag = TestTags.Settings.RECOVERY_SHOW,
    )
}

internal fun recoveryWordsText(words: List<String>): String =
    words.mapIndexed { index, word -> (index + 1).toString() + SettingsConstants.WORD_NUMBER_SUFFIX + word }.joinToString(SettingsConstants.LINE_BREAK)

@Composable
private fun RecoveryWords(words: List<String>) {
    val joined = recoveryWordsText(words)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.Settings.RECOVERY_WORDS).semantics { text = AnnotatedString(joined) },
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.ROW_SPACING),
    ) {
        words.withIndex().chunked(SettingsConstants.RECOVERY_GRID_COLUMNS).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                pair.forEach { (index, word) ->
                    Row(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.widthIn(min = SettingsDimens.WORD_NUMBER_WIDTH).secondaryItemAlpha(),
                        )
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag(listItem(TestTags.Settings.RECOVERY_WORD, index + 1)),
                        )
                    }
                }
            }
        }
    }
}
