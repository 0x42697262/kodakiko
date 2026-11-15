package dev.kodakiko.platform.device

import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private fun Window.setFlag(flag: Int, on: Boolean) {
    if (on) addFlags(flag) else clearFlags(flag)
}

object ScreenSecurity {
    fun apply(window: Window, secure: Boolean) = window.setFlag(WindowManager.LayoutParams.FLAG_SECURE, secure)
}

object KeepScreenOn {
    fun apply(window: Window, on: Boolean) = window.setFlag(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, on)
}

object SystemBars {
    fun apply(window: Window, darkTheme: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        controller(window).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    fun hide(window: Window) {
        controller(window).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun show(window: Window) = controller(window).show(WindowInsetsCompat.Type.systemBars())

    private fun controller(window: Window) = WindowCompat.getInsetsController(window, window.decorView)
}
