@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.js.JsDate
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun SyncSettingsScreen(viewModel: SyncSettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    SyncSettingsContent(state = state, onAction = viewModel::onAction)
}

internal fun syncedAtText(lastSuccessAt: Long): String =
    JsDate.toIsoString(lastSuccessAt.toDouble())
        .take(SettingsConstants.SYNCED_AT_CHARS)
        .replace(SettingsConstants.ISO_TIME_SEPARATOR, SettingsConstants.SYNCED_AT_SEPARATOR)

@Composable
fun SyncSettingsContent(state: SyncSettingsState, onAction: (SyncSettingsAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Settings.SYNC_SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.settings_row_sync),
                navigateUp = { onAction(SyncSettingsAction.Back) },
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
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
        ) {
            if (state.local) LocalSync(onAction) else ServerSync(state, onAction)
        }
    }
}

@Composable
private fun LocalSync(onAction: (SyncSettingsAction) -> Unit) {
    BodyText(text = stringResource(R.string.sync_local_only), testTag = TestTags.Settings.SYNC_LOCAL_ONLY)
    FormButton(text = stringResource(R.string.settings_connect), onClick = { onAction(SyncSettingsAction.Connect) }, testTag = TestTags.Settings.SYNC_CONNECT)
}

@Composable
private fun ServerSync(state: SyncSettingsState, onAction: (SyncSettingsAction) -> Unit) {
    val status = state.status
    val lastSynced = status.lastSuccessAt?.let { stringResource(R.string.settings_last_synced, syncedAtText(it)) } ?: stringResource(R.string.settings_not_synced)
    BodyText(text = lastSynced, testTag = TestTags.Settings.LAST_SYNCED)
    BodyText(text = stringResource(R.string.settings_waiting, status.queued), testTag = TestTags.Settings.WAITING)
    BodyText(text = stringResource(R.string.settings_failed, status.dead), testTag = TestTags.Settings.FAILED_COUNT)
    BodyText(text = stringResource(R.string.settings_waiting_wifi, status.waitingForWifi), testTag = TestTags.Settings.WAITING_WIFI)
    FormButton(
        text = stringResource(R.string.settings_sync_now),
        onClick = { onAction(SyncSettingsAction.SyncNow) },
        testTag = TestTags.Settings.SYNC_NOW,
        busy = state.syncing,
    )
    if (status.dead > 0) {
        FormButton(
            text = stringResource(R.string.settings_retry_failed),
            onClick = { onAction(SyncSettingsAction.RetryFailed) },
            testTag = TestTags.Settings.RETRY_FAILED,
            style = FormButtonStyle.SECONDARY,
        )
    }
    if (state.dead.isNotEmpty()) HorizontalDivider()
    val resources = LocalContext.current.resources
    state.dead.forEachIndexed { position, row ->
        BodyText(text = deadItemText(resources, row), testTag = listItem(TestTags.Settings.DEAD_ITEM, position))
    }
}

@Composable
internal fun BodyText(text: String, testTag: String? = null, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = if (testTag == null) modifier else modifier.testTag(testTag),
    )
}
