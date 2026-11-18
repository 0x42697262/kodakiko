package dev.kodakiko.ui.core.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import dev.kodakiko.data.db.ThemePreset
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.theme.colorscheme.BaseColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.CatppuccinColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.GreenAppleColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.KodakikoDefaultColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.LavenderColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.MidnightDuskColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.MonochromeColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.NordColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.StrawberryColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.TakoColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.TealTurquoiseColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.TidalWaveColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.TokyoNightColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.YinYangColorScheme
import dev.kodakiko.ui.core.theme.colorscheme.YotsubaColorScheme

object ThemePresets {
    private fun scheme(preset: ThemePreset): BaseColorScheme = when (preset) {
        ThemePreset.DEFAULT -> KodakikoDefaultColorScheme
        ThemePreset.CATPPUCCIN -> CatppuccinColorScheme
        ThemePreset.GREEN_APPLE -> GreenAppleColorScheme
        ThemePreset.LAVENDER -> LavenderColorScheme
        ThemePreset.MIDNIGHT_DUSK -> MidnightDuskColorScheme
        ThemePreset.NORD -> NordColorScheme
        ThemePreset.STRAWBERRY -> StrawberryColorScheme
        ThemePreset.TAKO -> TakoColorScheme
        ThemePreset.TEAL_TURQUOISE -> TealTurquoiseColorScheme
        ThemePreset.TIDAL_WAVE -> TidalWaveColorScheme
        ThemePreset.TOKYO_NIGHT -> TokyoNightColorScheme
        ThemePreset.YIN_YANG -> YinYangColorScheme
        ThemePreset.YOTSUBA -> YotsubaColorScheme
        ThemePreset.MONOCHROME -> MonochromeColorScheme
    }

    fun colorScheme(preset: ThemePreset, isDark: Boolean, isPureBlack: Boolean): ColorScheme =
        scheme(preset).getColorScheme(isDark = isDark, isPureBlack = isPureBlack, overrideDarkSurfaceContainers = true)

    @StringRes
    fun label(preset: ThemePreset): Int = when (preset) {
        ThemePreset.DEFAULT -> R.string.theme_preset_default
        ThemePreset.CATPPUCCIN -> R.string.theme_preset_catppuccin
        ThemePreset.GREEN_APPLE -> R.string.theme_preset_green_apple
        ThemePreset.LAVENDER -> R.string.theme_preset_lavender
        ThemePreset.MIDNIGHT_DUSK -> R.string.theme_preset_midnight_dusk
        ThemePreset.NORD -> R.string.theme_preset_nord
        ThemePreset.STRAWBERRY -> R.string.theme_preset_strawberry
        ThemePreset.TAKO -> R.string.theme_preset_tako
        ThemePreset.TEAL_TURQUOISE -> R.string.theme_preset_teal_turquoise
        ThemePreset.TIDAL_WAVE -> R.string.theme_preset_tidal_wave
        ThemePreset.TOKYO_NIGHT -> R.string.theme_preset_tokyo_night
        ThemePreset.YIN_YANG -> R.string.theme_preset_yin_yang
        ThemePreset.YOTSUBA -> R.string.theme_preset_yotsuba
        ThemePreset.MONOCHROME -> R.string.theme_preset_monochrome
    }
}
