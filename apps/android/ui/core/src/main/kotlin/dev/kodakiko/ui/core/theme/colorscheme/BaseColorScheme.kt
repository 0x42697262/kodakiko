// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.theme.colorscheme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal abstract class BaseColorScheme {
    abstract val darkScheme: ColorScheme
    abstract val lightScheme: ColorScheme

    private val surfaceContainer = Color(0xFF0C0C0C)
    private val surfaceContainerHigh = Color(0xFF131313)
    private val surfaceContainerHighest = Color(0xFF1B1B1B)

    fun getColorScheme(isDark: Boolean, isPureBlack: Boolean, overrideDarkSurfaceContainers: Boolean): ColorScheme =
        when {
            !isDark -> lightScheme

            !isPureBlack -> darkScheme

            !overrideDarkSurfaceContainers -> pureBlackScheme()

            else ->
                pureBlackScheme().copy(
                    surfaceVariant = surfaceContainer,
                    surfaceContainerLowest = surfaceContainer,
                    surfaceContainerLow = surfaceContainer,
                    surfaceContainer = surfaceContainer,
                    surfaceContainerHigh = surfaceContainerHigh,
                    surfaceContainerHighest = surfaceContainerHighest,
                )
        }

    private fun pureBlackScheme(): ColorScheme = darkScheme.copy(
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
    )
}
