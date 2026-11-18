package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.service.diagnostics.VectorReport
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator

@Immutable
sealed interface AdvancedMessage {
    data class Rebuilt(val entries: Int, val durationMs: Long) : AdvancedMessage

    data object Reset : AdvancedMessage
}

@Immutable
data class AdvancedSettingsState(
    val settings: DeviceSettings,
    val debugInfo: String?,
    val confirmingReset: Boolean,
    val message: AdvancedMessage?,
    val showDiagnostics: Boolean,
)

sealed interface AdvancedSettingsAction {
    data class SetTechnicalDetails(val on: Boolean) : AdvancedSettingsAction

    data object Rebuild : AdvancedSettingsAction

    data object AskReset : AdvancedSettingsAction

    data object DismissReset : AdvancedSettingsAction

    data object ConfirmReset : AdvancedSettingsAction

    data object ToggleDebug : AdvancedSettingsAction

    data object MessageShown : AdvancedSettingsAction

    data object OpenDiagnostics : AdvancedSettingsAction

    data object Back : AdvancedSettingsAction
}

class AdvancedSettingsViewModel(private val advanced: AdvancedSettingsDeps) : DeviceSettingsViewModel(advanced.settings, Route.SettingsAdvanced) {
    private data class Local(val debugInfo: String? = null, val confirmingReset: Boolean = false, val message: AdvancedMessage? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AdvancedSettingsState> = combine(advanced.settings.settings.settings, local, ::stateOf)
        .asState(stateOf(advanced.settings.settings.settings.value, local.value))

    fun onAction(action: AdvancedSettingsAction) {
        when (action) {
            is AdvancedSettingsAction.SetTechnicalDetails -> update { it.copy(showTechnicalDetails = action.on) }
            AdvancedSettingsAction.Rebuild -> rebuild()
            AdvancedSettingsAction.AskReset -> local.update { it.copy(confirmingReset = true) }
            AdvancedSettingsAction.DismissReset -> local.update { it.copy(confirmingReset = false) }
            AdvancedSettingsAction.ConfirmReset -> reset()
            AdvancedSettingsAction.ToggleDebug -> local.update { it.copy(debugInfo = if (it.debugInfo == null) advanced.diagnostics.debugInfoJson() else null) }
            AdvancedSettingsAction.MessageShown -> local.update { it.copy(message = null) }
            AdvancedSettingsAction.OpenDiagnostics -> send(SettingsEvent.Open(Route.SettingsDiagnostics))
            AdvancedSettingsAction.Back -> back()
        }
    }

    private fun rebuild() {
        advanced.index.clear()
        viewModelScope.launch {
            val report = attempt { advanced.index.build() } ?: return@launch
            local.update { it.copy(message = AdvancedMessage.Rebuilt(entries = report.entries, durationMs = report.durationMs)) }
        }
    }

    private fun reset() {
        update { DeviceSettingsDefaults.ANDROID }
        local.update { it.copy(confirmingReset = false, message = AdvancedMessage.Reset) }
    }

    private fun stateOf(settings: DeviceSettings, current: Local): AdvancedSettingsState = AdvancedSettingsState(
        settings = settings,
        debugInfo = current.debugInfo,
        confirmingReset = current.confirmingReset,
        message = current.message,
        showDiagnostics = advanced.showDiagnostics,
    )
}

@Immutable
sealed interface DiagnosticsLine {
    data object Running : DiagnosticsLine

    data class Summary(val passed: Int, val total: Int) : DiagnosticsLine

    data class Case(val name: String, val passed: Boolean, val durationMs: Long) : DiagnosticsLine

    data class Failed(val message: String) : DiagnosticsLine
}

@Immutable
data class DiagnosticsState(val running: Boolean, val lines: List<DiagnosticsLine>)

sealed interface DiagnosticsAction {
    data object RunVectors : DiagnosticsAction

    data object Back : DiagnosticsAction
}

private fun linesOf(report: VectorReport): List<DiagnosticsLine> =
    listOf(DiagnosticsLine.Summary(passed = report.passed, total = report.total)) +
        report.cases.map { DiagnosticsLine.Case(name = it.name, passed = it.passed, durationMs = it.durationMs) }

class DiagnosticsViewModel(private val deps: AdvancedSettingsDeps) : ViewModel() {
    private val current = MutableStateFlow(DiagnosticsState(running = false, lines = emptyList()))
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<DiagnosticsState> = current.asStateFlow()

    fun onAction(action: DiagnosticsAction) {
        when (action) {
            DiagnosticsAction.RunVectors -> run()
            DiagnosticsAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun run() {
        if (current.value.running) return
        current.value = DiagnosticsState(running = true, lines = listOf(DiagnosticsLine.Running))
        viewModelScope.launch {
            val lines = try {
                linesOf(deps.diagnostics.runVectors())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                listOf(DiagnosticsLine.Failed(failure.message ?: failure.toString()))
            }
            current.value = DiagnosticsState(running = false, lines = lines)
        }
    }
}

@Immutable
data class AboutState(val appVersion: String, val serverVersion: String?, val licenses: List<OpenSourceLicense>)

sealed interface AboutAction {
    data object Back : AboutAction
}

class AboutViewModel(private val deps: AboutDeps) : ViewModel() {
    private val licenses = MutableStateFlow<List<OpenSourceLicense>>(emptyList())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    private val serverVersion = deps.settings.config.current()?.serverVersion

    val state: StateFlow<AboutState> = licenses.map { AboutState(appVersion = deps.appVersion, serverVersion = serverVersion, licenses = it) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS),
        AboutState(appVersion = deps.appVersion, serverVersion = serverVersion, licenses = licenses.value),
    )

    init {
        viewModelScope.launch {
            val collator = Collator.getInstance()
            licenses.value = attempt { deps.licenses.load() }.orEmpty().sortedWith(compareBy(collator) { it.name })
        }
    }

    fun onAction(action: AboutAction) {
        when (action) {
            AboutAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }
}
