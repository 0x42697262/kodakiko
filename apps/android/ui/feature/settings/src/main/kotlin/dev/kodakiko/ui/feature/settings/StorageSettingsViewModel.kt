package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.media.CacheUsage
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class StorageSettingsState(
    val settings: DeviceSettings,
    val usage: CacheUsage?,
    val device: DeviceStorage?,
    val local: Boolean,
    val confirmingClear: Boolean,
)

sealed interface StorageSettingsAction {
    data class SetCacheCap(val bytes: Long) : StorageSettingsAction

    data object Clear : StorageSettingsAction

    data object ConfirmClear : StorageSettingsAction

    data object DismissClear : StorageSettingsAction

    data object Export : StorageSettingsAction

    data object Import : StorageSettingsAction

    data object Back : StorageSettingsAction
}

private data class StorageFigures(val usage: CacheUsage?, val device: DeviceStorage?, val confirmingClear: Boolean)

class StorageSettingsViewModel(deps: SettingsDeps) : DeviceSettingsViewModel(deps, Route.SettingsStorage) {
    private val figures = MutableStateFlow(StorageFigures(usage = null, device = null, confirmingClear = false))

    val state: StateFlow<StorageSettingsState> = combine(deps.settings.settings, deps.account.mode, figures) { settings, mode, current ->
        stateOf(settings, mode, current)
    }.asState(stateOf(deps.settings.settings.value, deps.account.mode.value, figures.value))

    init {
        viewModelScope.launch {
            val device = attempt { deps.storage.device() }
            figures.update { it.copy(device = device) }
            reloadUsage()
        }
    }

    fun onAction(action: StorageSettingsAction) {
        when (action) {
            is StorageSettingsAction.SetCacheCap -> setCacheCap(action.bytes)
            StorageSettingsAction.Clear -> figures.update { it.copy(confirmingClear = true) }
            StorageSettingsAction.ConfirmClear -> clear()
            StorageSettingsAction.DismissClear -> figures.update { it.copy(confirmingClear = false) }
            StorageSettingsAction.Export -> send(SettingsEvent.Open(Route.SettingsExport))
            StorageSettingsAction.Import -> send(SettingsEvent.Open(Route.SettingsImport))
            StorageSettingsAction.Back -> back()
        }
    }

    private fun setCacheCap(bytes: Long) {
        update { it.copy(originalCacheCapBytes = bytes) }
        viewModelScope.launch {
            attempt { deps.storage.enforceCap() }
            reloadUsage()
        }
    }

    private fun clear() {
        figures.update { it.copy(confirmingClear = false) }
        viewModelScope.launch {
            attempt { deps.storage.clearOriginals() }
            reloadUsage()
        }
    }

    private suspend fun reloadUsage() {
        attempt { deps.storage.usage() }?.let { usage -> figures.update { it.copy(usage = usage) } }
    }

    private fun stateOf(settings: DeviceSettings, mode: AccountMode, current: StorageFigures): StorageSettingsState = StorageSettingsState(
        settings = settings,
        usage = current.usage,
        device = current.device,
        local = mode == AccountMode.LOCAL,
        confirmingClear = current.confirmingClear,
    )
}
