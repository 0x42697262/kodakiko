package dev.kodakiko.ui.core.theme.colorscheme

import androidx.compose.material3.ColorScheme
import dev.kodakiko.ui.core.theme.KodakikoColorScheme

internal object KodakikoDefaultColorScheme : BaseColorScheme() {
    override val darkScheme: ColorScheme = KodakikoColorScheme.darkScheme
    override val lightScheme: ColorScheme = KodakikoColorScheme.lightScheme
}
