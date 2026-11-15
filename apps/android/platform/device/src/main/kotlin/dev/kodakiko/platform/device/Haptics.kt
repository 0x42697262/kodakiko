package dev.kodakiko.platform.device

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticKind { SELECT, TOGGLE, SHUTTER, RECORD_START, RECORD_STOP, REFRESH }

class Haptics(private val view: () -> View?, private val enabled: () -> Boolean) {
    fun tick(kind: HapticKind) {
        runCatching {
            if (enabled()) view()?.performHapticFeedback(feedbackConstant(kind))
        }
    }

    private fun feedbackConstant(kind: HapticKind): Int = when (kind) {
        HapticKind.SELECT -> HapticFeedbackConstants.LONG_PRESS
        HapticKind.TOGGLE -> if (hasToggleConstants()) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.VIRTUAL_KEY
        HapticKind.SHUTTER -> HapticFeedbackConstants.VIRTUAL_KEY
        HapticKind.RECORD_START -> if (hasGestureConstants()) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.LONG_PRESS
        HapticKind.RECORD_STOP -> if (hasGestureConstants()) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.LONG_PRESS
        HapticKind.REFRESH -> if (hasGestureConstants()) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    }

    private fun hasToggleConstants(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun hasGestureConstants(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
