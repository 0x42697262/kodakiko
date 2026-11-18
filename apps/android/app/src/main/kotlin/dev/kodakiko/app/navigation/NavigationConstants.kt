package dev.kodakiko.app.navigation

import androidx.compose.ui.unit.dp

object NavigationConstants {
    const val NAVIGATION_LOCK_MS = 600L
    const val MOTION_DURATION_MS = 300
    const val OUTGOING_SHARE = 0.35f
    const val TAB_FADE_DURATION_MS = 200
    const val BACKWARD_Z_INDEX = -1f
    val SLIDE_DISTANCE = 30.dp
    const val HOME_SCREEN_KEY = "home"
    const val TAB_NAVIGATOR_KEY = "homeTabs"
    const val TAB_STATE_KEY = "currentTab"
    const val TRANSITION_STATE_KEY = "transition"
    const val TRANSITION_LABEL = "transition"
    const val TAB_CONTENT_LABEL = "tabContent"
    const val STATE_SUBSCRIPTION_MS = 5_000L
    const val PREPARE_HOME_TIMEOUT_MS = 5_000L
}
