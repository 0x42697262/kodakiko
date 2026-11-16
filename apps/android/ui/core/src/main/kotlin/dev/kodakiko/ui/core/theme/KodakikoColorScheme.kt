// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.theme

import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme

@ChecksSdkIntAtLeast(api = ThemeConstants.DYNAMIC_COLOR_MIN_SDK)
fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= ThemeConstants.DYNAMIC_COLOR_MIN_SDK

internal object KodakikoColorScheme {
    val lightScheme: ColorScheme = with(ThemeConstants.Light) {
        lightColorScheme(
            primary = PRIMARY,
            onPrimary = ON_PRIMARY,
            primaryContainer = PRIMARY,
            onPrimaryContainer = ON_PRIMARY,
            inversePrimary = ThemeConstants.Dark.PRIMARY,
            secondaryContainer = SECONDARY_CONTAINER,
            onSecondaryContainer = TEXT,
            background = BACKGROUND,
            onBackground = TEXT,
            surface = BACKGROUND,
            onSurface = TEXT,
            surfaceVariant = SURFACE,
            onSurfaceVariant = TEXT_MUTED,
            outline = BORDER,
            error = DANGER,
            surfaceContainerLow = BACKGROUND,
            surfaceContainer = SURFACE,
            surfaceContainerHigh = SURFACE,
        )
    }

    val darkScheme: ColorScheme = with(ThemeConstants.Dark) {
        darkColorScheme(
            primary = PRIMARY,
            onPrimary = ON_PRIMARY,
            primaryContainer = PRIMARY,
            onPrimaryContainer = ON_PRIMARY,
            inversePrimary = ThemeConstants.Light.PRIMARY,
            secondaryContainer = SECONDARY_CONTAINER,
            onSecondaryContainer = TEXT,
            background = BACKGROUND,
            onBackground = TEXT,
            surface = BACKGROUND,
            onSurface = TEXT,
            surfaceVariant = SURFACE,
            onSurfaceVariant = TEXT_MUTED,
            outline = BORDER,
            error = DANGER,
            surfaceContainerLow = BACKGROUND,
            surfaceContainer = SURFACE,
            surfaceContainerHigh = SURFACE,
        )
    }

    fun getColorScheme(
        context: Context,
        isDark: Boolean,
        isPureBlack: Boolean,
        isDynamic: Boolean,
    ): ColorScheme {
        val base = when {
            isDynamic && isDynamicColorAvailable() -> if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            isDark -> darkScheme
            else -> lightScheme
        }
        if (!isDark || !isPureBlack) return base
        return base.copy(
            background = ThemeConstants.PureBlack.BACKGROUND,
            surface = ThemeConstants.PureBlack.BACKGROUND,
            surfaceContainerLow = ThemeConstants.PureBlack.BACKGROUND,
            surfaceVariant = ThemeConstants.PureBlack.SURFACE,
            surfaceContainer = ThemeConstants.PureBlack.SURFACE,
            surfaceContainerHigh = ThemeConstants.PureBlack.SURFACE,
        )
    }
}
