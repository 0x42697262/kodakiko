@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.kodakiko.log.DebugLogInfo
import dev.kodakiko.log.LogLevel
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.material.KodakikoSnackbarHost
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.SnackbarMessage
import dev.kodakiko.ui.core.components.material.showMessage
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.settings.widget.SwitchPreferenceWidget
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface DebugLogPort {
    val info: Flow<DebugLogInfo>
    val lines: Flow<List<String>>

    suspend fun setEnabled(on: Boolean)

    suspend fun clear()

    suspend fun copy(): Int

    suspend fun share()
}

sealed interface DebugLogMessage {
    data class Copied(val lines: Int) : DebugLogMessage

    data object Cleared : DebugLogMessage

    data object Failed : DebugLogMessage
}

@Immutable
data class DebugLogState(
    val info: DebugLogInfo = DebugLogInfo.EMPTY,
    val lines: List<String> = emptyList(),
    val confirmingClear: Boolean = false,
    val message: DebugLogMessage? = null,
)

sealed interface DebugLogAction {
    data class SetEnabled(val on: Boolean) : DebugLogAction

    data object Copy : DebugLogAction

    data object Share : DebugLogAction

    data object AskClear : DebugLogAction

    data object ConfirmClear : DebugLogAction

    data object DismissClear : DebugLogAction

    data object MessageShown : DebugLogAction

    data object Back : DebugLogAction
}

private data class DebugLogUi(val confirmingClear: Boolean = false, val message: DebugLogMessage? = null)

class DebugLogViewModel(private val deps: AdvancedSettingsDeps) : ViewModel() {
    private val ui = MutableStateFlow(DebugLogUi())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<DebugLogState> = combine(deps.debugLog.info, deps.debugLog.lines, ui) { info, lines, current ->
        DebugLogState(info = info, lines = lines, confirmingClear = current.confirmingClear, message = current.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS), DebugLogState())

    fun onAction(action: DebugLogAction) {
        when (action) {
            is DebugLogAction.SetEnabled -> run { deps.debugLog.setEnabled(action.on) }
            DebugLogAction.Copy -> run { ui.update { it.copy(message = DebugLogMessage.Copied(deps.debugLog.copy())) } }
            DebugLogAction.Share -> run { deps.debugLog.share() }
            DebugLogAction.AskClear -> ui.update { it.copy(confirmingClear = true) }
            DebugLogAction.DismissClear -> ui.update { it.copy(confirmingClear = false) }
            DebugLogAction.ConfirmClear -> {
                ui.update { it.copy(confirmingClear = false) }
                run {
                    deps.debugLog.clear()
                    ui.update { it.copy(message = DebugLogMessage.Cleared) }
                }
            }
            DebugLogAction.MessageShown -> ui.update { it.copy(message = null) }
            DebugLogAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ui.update { it.copy(message = DebugLogMessage.Failed) }
            }
        }
    }
}

@Composable
fun DebugLogScreen(viewModel: DebugLogViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    DebugLogContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun DebugLogContent(state: DebugLogState, onAction: (DebugLogAction) -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size)
    }
    Scaffold(
        modifier = Modifier.testTag(TestTags.DebugLog.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.debug_log_title),
                navigateUp = { onAction(DebugLogAction.Back) },
                navigateUpTestTag = TestTags.Settings.BACK,
                scrollBehavior = it,
                actions = {
                    IconButton(onClick = { onAction(DebugLogAction.Copy) }, enabled = state.info.entries > 0, modifier = Modifier.testTag(TestTags.DebugLog.COPY)) {
                        Icon(imageVector = KodakikoIcons.ContentCopy, contentDescription = stringResource(R.string.debug_log_copy))
                    }
                    IconButton(onClick = { onAction(DebugLogAction.Share) }, enabled = state.info.entries > 0, modifier = Modifier.testTag(TestTags.DebugLog.SHARE)) {
                        Icon(imageVector = KodakikoIcons.Share, contentDescription = stringResource(R.string.debug_log_share))
                    }
                    IconButton(onClick = { onAction(DebugLogAction.AskClear) }, enabled = state.info.entries > 0, modifier = Modifier.testTag(TestTags.DebugLog.CLEAR)) {
                        Icon(imageVector = KodakikoIcons.Delete, contentDescription = stringResource(R.string.debug_log_clear))
                    }
                },
            )
        },
        snackbarHost = { KodakikoSnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(contentPadding).testTag(TestTags.DebugLog.LIST)) {
            item {
                SwitchPreferenceWidget(
                    title = stringResource(R.string.debug_log_switch),
                    subtitle = stringResource(R.string.debug_log_switch_summary),
                    checked = state.info.enabled,
                    onCheckedChanged = { onAction(DebugLogAction.SetEnabled(it)) },
                    switchTestTag = TestTags.DebugLog.SWITCH,
                )
                HorizontalDivider()
            }
            if (state.lines.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.debug_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(SettingsDimens.SCREEN_PADDING).testTag(TestTags.DebugLog.EMPTY),
                    )
                }
            }
            itemsIndexed(state.lines) { position, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = lineColor(line),
                    modifier = Modifier
                        .padding(horizontal = SettingsDimens.SCREEN_PADDING, vertical = SettingsDimens.LOG_LINE_PADDING)
                        .testTag(listItem(TestTags.DebugLog.LINE, position)),
                )
            }
        }
    }
    DebugLogMessageEffect(state.message, snackbarHostState, onAction)
    if (state.confirmingClear) {
        ConfirmSheet(
            sheetTag = TestTags.DebugLog.CLEAR_SHEET,
            message = stringResource(R.string.debug_log_clear_confirm),
            confirmText = stringResource(R.string.debug_log_clear),
            confirmTag = TestTags.DebugLog.CLEAR_CONFIRM,
            onConfirm = { onAction(DebugLogAction.ConfirmClear) },
            onDismiss = { onAction(DebugLogAction.DismissClear) },
            danger = true,
            cancelText = stringResource(R.string.settings_cancel),
            cancelTag = TestTags.Settings.CANCEL,
        )
    }
}

@Composable
private fun lineColor(line: String): Color = when {
    line.contains(LogLevel.ERROR.name) -> MaterialTheme.colorScheme.error
    line.contains(LogLevel.WARN.name) -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun debugLogSummary(info: DebugLogInfo): String =
    pluralStringResource(R.plurals.debug_log_summary, info.entries, info.entries, formatBytes(info.sizeBytes))

@Composable
private fun DebugLogMessageEffect(message: DebugLogMessage?, hostState: SnackbarHostState, onAction: (DebugLogAction) -> Unit) {
    val resources = LocalResources.current
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(message, hostState) {
        val text = when (message) {
            null -> return@LaunchedEffect
            is DebugLogMessage.Copied -> resources.getQuantityString(R.plurals.debug_log_copied, message.lines, message.lines)
            DebugLogMessage.Cleared -> resources.getString(R.string.debug_log_cleared)
            DebugLogMessage.Failed -> resources.getString(R.string.debug_log_failed)
        }
        hostState.showMessage(SnackbarMessage(text, TestTags.DebugLog.MESSAGE))
        currentOnAction(DebugLogAction.MessageShown)
    }
}
