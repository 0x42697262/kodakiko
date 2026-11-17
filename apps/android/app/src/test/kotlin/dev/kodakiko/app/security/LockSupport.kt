package dev.kodakiko.app.security

import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import dev.kodakiko.app.navigation.AppWiringHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf

fun AppWiringHarness.waitIdling(timeoutMs: Long, condition: () -> Boolean): Boolean {
    try {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    } catch (_: ComposeTimeoutException) {
    }
    shadowOf(Looper.getMainLooper()).idle()
    return condition()
}

fun AppWiringHarness.lockAndWait() {
    val locking = CoroutineScope(Dispatchers.Main.immediate).launch { app.graph.lockControl.lockNow() }
    check(waitIdling(AppWiringHarness.UNLOCK_WAIT_MS) { locking.isCompleted }) { "lockNow did not complete" }
}
