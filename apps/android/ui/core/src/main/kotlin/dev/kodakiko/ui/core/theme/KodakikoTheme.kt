// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.kodakiko.data.db.AppTheme
import dev.kodakiko.data.db.DeviceSettingsDefaults

@Composable
fun KodakikoTheme(
    appTheme: AppTheme = DeviceSettingsDefaults.ANDROID.appTheme,
    pureBlack: Boolean = DeviceSettingsDefaults.ANDROID.pureBlack,
    dynamicColor: Boolean = DeviceSettingsDefaults.ANDROID.dynamicColor,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark = when (appTheme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    MaterialTheme(
        colorScheme = remember(context, isDark, pureBlack, dynamicColor) {
            KodakikoColorScheme.getColorScheme(
                context = context,
                isDark = isDark,
                isPureBlack = pureBlack,
                isDynamic = dynamicColor,
            )
        },
        content = content,
    )
}
