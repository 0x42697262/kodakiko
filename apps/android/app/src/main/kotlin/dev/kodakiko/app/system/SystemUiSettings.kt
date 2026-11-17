package dev.kodakiko.app.system

import androidx.compose.runtime.Immutable
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults

@Immutable
data class SystemUiSettings(val fullScreen: Boolean, val secureScreen: Boolean) {
    companion object {
        fun of(settings: DeviceSettings, loaded: Boolean): SystemUiSettings {
            val source = if (loaded) settings else DeviceSettingsDefaults.ANDROID
            return SystemUiSettings(fullScreen = source.fullScreen, secureScreen = source.secureScreen)
        }

        fun of(settings: DeviceSettings, loaded: Boolean, unlocked: Boolean): SystemUiSettings {
            val applied = of(settings, loaded)
            return if (unlocked) applied else applied.copy(secureScreen = true)
        }

        val STARTUP: SystemUiSettings = of(DeviceSettingsDefaults.ANDROID, loaded = false)
    }
}
