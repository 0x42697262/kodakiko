package dev.kodakiko.app.navigation

import androidx.compose.runtime.Immutable
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.secure.AccountMode

@Immutable
data class HomeSettings(val hideBarsOnScroll: Boolean, val sharedTabVisible: Boolean) {
    companion object {
        fun of(settings: DeviceSettings, mode: AccountMode, config: PublicConfigDto?): HomeSettings = HomeSettings(
            hideBarsOnScroll = settings.hideBarsOnScroll,
            sharedTabVisible = mode == AccountMode.SERVER && config?.sharingEnabled == true,
        )
    }
}
