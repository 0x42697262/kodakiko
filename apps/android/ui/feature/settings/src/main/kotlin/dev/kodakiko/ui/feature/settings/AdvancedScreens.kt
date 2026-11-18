// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.material.KodakikoSnackbarHost
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.SnackbarMessage
import dev.kodakiko.ui.core.components.material.showMessage
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.settings.Preference
import dev.kodakiko.ui.core.settings.widget.PreferenceGroupHeader
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun AdvancedSettingsScreen(viewModel: AdvancedSettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    AdvancedSettingsContent(state = state, onAction = viewModel::onAction, highlightKey = viewModel.highlightKey)
}

@Composable
fun AdvancedSettingsContent(state: AdvancedSettingsState, onAction: (AdvancedSettingsAction) -> Unit, highlightKey: String? = null) {
    val snackbarHostState = remember { SnackbarHostState() }
    PinnedPreferencePage(
        title = stringResource(R.string.advanced_title),
        items = advancedItems(state, onAction),
        screenTag = TestTags.Advanced.SCREEN,
        onBack = { onAction(AdvancedSettingsAction.Back) },
        backTag = TestTags.Settings.BACK,
        highlightKey = highlightKey,
        snackbarHost = { KodakikoSnackbarHost(snackbarHostState) },
    )
    AdvancedMessageEffect(state.message, snackbarHostState, onAction)
    if (state.confirmingReset) {
        ConfirmSheet(
            sheetTag = TestTags.Advanced.RESET_SHEET,
            message = stringResource(R.string.advanced_reset_confirm),
            confirmText = stringResource(R.string.advanced_reset),
            confirmTag = TestTags.Advanced.RESET_CONFIRM,
            onConfirm = { onAction(AdvancedSettingsAction.ConfirmReset) },
            onDismiss = { onAction(AdvancedSettingsAction.DismissReset) },
            danger = true,
            cancelText = stringResource(R.string.settings_cancel),
            cancelTag = TestTags.Settings.CANCEL,
        )
    }
}

@Composable
private fun AdvancedMessageEffect(message: AdvancedMessage?, hostState: SnackbarHostState, onAction: (AdvancedSettingsAction) -> Unit) {
    val resources = LocalResources.current
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(message, hostState) {
        val text = when (message) {
            null -> return@LaunchedEffect
            is AdvancedMessage.Rebuilt -> resources.getString(R.string.advanced_rebuilt, message.entries, message.durationMs)
            AdvancedMessage.Reset -> resources.getString(R.string.advanced_reset_done)
        }
        hostState.showMessage(SnackbarMessage(text, TestTags.Advanced.MESSAGE))
        currentOnAction(AdvancedSettingsAction.MessageShown)
    }
}

@Composable
private fun advancedItems(state: AdvancedSettingsState, onAction: (AdvancedSettingsAction) -> Unit): List<Preference> = listOf(
    Preference.PreferenceGroup(
        title = stringResource(R.string.advanced_group_general),
        preferenceItems = listOf(
            Preference.PreferenceItem.SwitchPreference(
                value = state.settings.showTechnicalDetails,
                title = stringResource(R.string.prefs_technical_details),
                subtitle = stringResource(R.string.prefs_technical_details_summary),
                testTag = TestTags.Settings.TECHNICAL_DETAILS,
                onValueChanged = { onAction(AdvancedSettingsAction.SetTechnicalDetails(it)) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.advanced_rebuild),
                subtitle = stringResource(R.string.advanced_rebuild_summary),
                icon = KodakikoIcons.Refresh,
                testTag = TestTags.Advanced.REBUILD,
                onClick = { onAction(AdvancedSettingsAction.Rebuild) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.advanced_reset),
                subtitle = stringResource(R.string.advanced_reset_summary),
                icon = KodakikoIcons.RestartAlt,
                testTag = TestTags.Advanced.RESET,
                onClick = { onAction(AdvancedSettingsAction.AskReset) },
            ),
        ),
    ),
    Preference.PreferenceGroup(
        title = stringResource(R.string.advanced_group_debug_log),
        preferenceItems = listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.debug_log_view),
                subtitle = stringResource(R.string.debug_log_switch_summary),
                icon = KodakikoIcons.Article,
                testTag = TestTags.Advanced.DEBUG_LOG,
                onClick = { onAction(AdvancedSettingsAction.OpenDebugLog) },
            ),
        ),
    ),
    Preference.PreferenceGroup(
        title = stringResource(R.string.advanced_group_debug_info),
        preferenceItems = listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.advanced_debug),
                subtitle = stringResource(R.string.advanced_debug_summary),
                icon = KodakikoIcons.DataObject,
                testTag = TestTags.Advanced.DEBUG,
                onClick = { onAction(AdvancedSettingsAction.ToggleDebug) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.diagnostics_title),
                icon = KodakikoIcons.BugReport,
                testTag = TestTags.Advanced.DIAGNOSTICS,
                visible = state.showDiagnostics,
                onClick = { onAction(AdvancedSettingsAction.OpenDiagnostics) },
            ),
            Preference.PreferenceItem.CustomPreference(title = "", visible = state.debugInfo != null) {
                DebugText(state.debugInfo.orEmpty())
            },
        ),
    ),
)

@Composable
private fun DebugText(json: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(SettingsDimens.SCREEN_PADDING),
    ) {
        SelectionContainer {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(SettingsDimens.CARD_PADDING).testTag(TestTags.Advanced.DEBUG_TEXT),
            )
        }
    }
}

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    DiagnosticsContent(state = state, onAction = viewModel::onAction)
}

@Composable
private fun diagnosticsLineText(line: DiagnosticsLine): String = when (line) {
    DiagnosticsLine.Running -> stringResource(R.string.diagnostics_running)
    is DiagnosticsLine.Summary -> stringResource(R.string.diagnostics_vector_summary, line.passed, line.total)
    is DiagnosticsLine.Case -> stringResource(
        if (line.passed) R.string.diagnostics_vector_case_pass else R.string.diagnostics_vector_case_fail,
        line.name,
        line.durationMs,
    )
    is DiagnosticsLine.Failed -> stringResource(R.string.diagnostics_failed, line.message)
}

@Composable
fun DiagnosticsContent(state: DiagnosticsState, onAction: (DiagnosticsAction) -> Unit) {
    Scaffold(
        modifier = Modifier.testTag(TestTags.Diagnostics.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.diagnostics_title),
                navigateUp = { onAction(DiagnosticsAction.Back) },
                navigateUpTestTag = TestTags.Settings.BACK,
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(SettingsDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.ITEM_SPACING),
        ) {
            FormButton(
                text = stringResource(R.string.diagnostics_run_vectors),
                onClick = { onAction(DiagnosticsAction.RunVectors) },
                testTag = TestTags.Diagnostics.RUN_VECTORS,
                enabled = !state.running,
            )
            state.lines.forEachIndexed { position, line ->
                Text(
                    text = diagnosticsLineText(line),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if ((line is DiagnosticsLine.Case && !line.passed) || line is DiagnosticsLine.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(listItem(TestTags.Diagnostics.LINE, position)),
                )
            }
        }
    }
}

@Composable
fun AboutScreen(viewModel: AboutViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    AboutContent(state = state, onAction = viewModel::onAction)
}

internal fun licenseText(license: OpenSourceLicense): String =
    license.name + SettingsConstants.WORD_SEPARATOR + license.version + SettingsConstants.LICENSE_SEPARATOR + license.license

@Composable
fun AboutContent(state: AboutState, onAction: (AboutAction) -> Unit) {
    Scaffold(
        modifier = Modifier.testTag(TestTags.Settings.ABOUT_SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.settings_row_about),
                navigateUp = { onAction(AboutAction.Back) },
                navigateUpTestTag = TestTags.Settings.BACK,
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
            LabeledValue(stringResource(R.string.settings_app_version), state.appVersion, TestTags.Settings.APP_VERSION)
            LabeledValue(stringResource(R.string.settings_server_version), state.serverVersion ?: SettingsConstants.UNKNOWN_VALUE, TestTags.Settings.SERVER_VERSION)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(SettingsDimens.SCREEN_PADDING),
            ) {
                Text(
                    text = stringResource(R.string.about_mihon_attribution),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(SettingsDimens.CARD_PADDING).testTag(TestTags.Settings.MIHON_ATTRIBUTION),
                )
            }
            HorizontalDivider()
            PreferenceGroupHeader(title = stringResource(R.string.settings_licenses))
            if (state.licenses.isEmpty()) {
                BodyText(text = stringResource(R.string.settings_licenses_empty), testTag = TestTags.Settings.LICENSES_EMPTY, modifier = Modifier.padding(SettingsDimens.ITEM_PADDING))
            }
            state.licenses.forEachIndexed { position, license ->
                Text(
                    text = licenseText(license),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(SettingsDimens.ITEM_PADDING).secondaryItemAlpha().testTag(listItem(TestTags.Settings.LICENSE, position)),
                )
            }
        }
    }
}
