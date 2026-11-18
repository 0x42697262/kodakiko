// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.app.system

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

object EdgeToEdge {
    fun apply(activity: ComponentActivity, darkTheme: Boolean) {
        val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
        val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        val style = if (darkTheme) darkStyle else lightStyle
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
