package dev.kodakiko.app.navigation

import androidx.compose.runtime.Immutable
import dev.kodakiko.data.db.DeviceSettings

@Immutable
data class HomeSettings(val hideBarsOnScroll: Boolean, val sharedTabVisible: Boolean) {
    companion object {
        fun of(settings: DeviceSettings, sharingAvailable: Boolean): HomeSettings =
            HomeSettings(hideBarsOnScroll = settings.hideBarsOnScroll, sharedTabVisible = sharingAvailable)
    }
}
