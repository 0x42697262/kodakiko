package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

abstract class DeviceSettingsViewModel(protected val deps: SettingsDeps, route: Route) : ViewModel() {
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val highlightKey: String? = deps.highlight.take(route)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val settings: StateFlow<DeviceSettings> get() = deps.settings.settings

    fun update(change: (DeviceSettings) -> DeviceSettings) {
        deps.settings.update(change)
    }

    fun back() {
        send(SettingsEvent.Back)
    }

    protected fun send(event: SettingsEvent) {
        channel.trySend(event)
    }

    protected fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS), initial)
}

class AppearanceSettingsViewModel(deps: SettingsDeps) : DeviceSettingsViewModel(deps, Route.SettingsAppearance) {
    val dynamicColorAvailable: Boolean = deps.dynamicColorAvailable
}

class GallerySettingsViewModel(deps: SettingsDeps) : DeviceSettingsViewModel(deps, Route.SettingsGallery)

@Immutable
data class CaptureSettingsState(val settings: DeviceSettings, val locationDenied: Boolean)

sealed interface CaptureSettingsAction {
    data class SetLocation(val on: Boolean) : CaptureSettingsAction

    data object Back : CaptureSettingsAction
}

class CaptureSettingsViewModel(deps: SettingsDeps) : DeviceSettingsViewModel(deps, Route.SettingsCapture) {
    private val locationDenied = MutableStateFlow(false)

    val state: StateFlow<CaptureSettingsState> = combine(deps.settings.settings, locationDenied, ::CaptureSettingsState)
        .asState(CaptureSettingsState(deps.settings.settings.value, locationDenied.value))

    fun onAction(action: CaptureSettingsAction) {
        when (action) {
            is CaptureSettingsAction.SetLocation -> setLocation(action.on)
            CaptureSettingsAction.Back -> back()
        }
    }

    private fun setLocation(on: Boolean) {
        locationDenied.value = false
        update { it.copy(locationForCaptures = on) }
        if (!on) return
        viewModelScope.launch {
            val found = attempt { deps.location.probe(SettingsConstants.LOCATION_PROBE_MS) } ?: false
            if (found) return@launch
            locationDenied.value = true
            update { it.copy(locationForCaptures = false) }
        }
    }
}
