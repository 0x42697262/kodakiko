package dev.kodakiko.ui.core.theme

import androidx.compose.ui.graphics.Color

object ThemeConstants {
    object Light {
        val PRIMARY = Color(0xFF2F5FD0)
        val ON_PRIMARY = Color(0xFFFFFFFF)
        val SECONDARY_CONTAINER = Color(0xFFDCE3F9)
        val BACKGROUND = Color(0xFFFFFFFF)
        val SURFACE = Color(0xFFF2F2F7)
        val TEXT = Color(0xFF111114)
        val TEXT_MUTED = Color(0xFF5C5C66)
        val BORDER = Color(0xFFD1D1D6)
        val DANGER = Color(0xFFC62828)
    }

    object Dark {
        val PRIMARY = Color(0xFF7AA2FF)
        val ON_PRIMARY = Color(0xFF0B0B0D)
        val SECONDARY_CONTAINER = Color(0xFF2A3354)
        val BACKGROUND = Color(0xFF0B0B0D)
        val SURFACE = Color(0xFF1C1C1F)
        val TEXT = Color(0xFFF2F2F5)
        val TEXT_MUTED = Color(0xFFA1A1AA)
        val BORDER = Color(0xFF3A3A3F)
        val DANGER = Color(0xFFFF6B6B)
    }

    object PureBlack {
        val BACKGROUND = Color(0xFF000000)
        val SURFACE = Color(0xFF121212)
    }

    const val DYNAMIC_COLOR_MIN_SDK = android.os.Build.VERSION_CODES.S
}
