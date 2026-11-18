package dev.kodakiko.app.system

import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.kodakiko.platform.device.ScreenSecurity
import dev.kodakiko.platform.device.SystemBars
import dev.kodakiko.ui.core.util.LocalFullScreen

@Composable
fun SystemUi(
    settings: SystemUiSettings,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val activity = LocalActivity.current as? ComponentActivity
    if (activity != null) {
        val window = activity.window
        DisposableEffect(activity, darkTheme) {
            EdgeToEdge.apply(activity, darkTheme)
            onDispose {}
        }
        DisposableEffect(window, settings.secureScreen) {
            ScreenSecurity.apply(window, settings.secureScreen)
            onDispose {}
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(window, settings.fullScreen, lifecycle) {
            applyBars(window, settings.fullScreen)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) applyBars(window, settings.fullScreen)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }
    CompositionLocalProvider(LocalFullScreen provides settings.fullScreen, content = content)
}

private fun applyBars(window: Window, fullScreen: Boolean) {
    if (fullScreen) SystemBars.hide(window) else SystemBars.show(window)
}
