// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.app.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import dev.kodakiko.ui.core.navigation.Route

enum class TransitionKind {
    SLIDE,
    SLIDE_UP,
    FADE,
}

data class RouteTransition(val kind: TransitionKind, val forward: Boolean)

object RouteTransitions {
    fun kindOf(route: Route): TransitionKind = when (route) {
        is Route.Capture -> TransitionKind.SLIDE_UP
        is Route.Play -> TransitionKind.FADE
        else -> TransitionKind.SLIDE
    }

    fun contentTransform(transition: RouteTransition, slideDistance: Int): ContentTransform = when (transition.kind) {
        TransitionKind.SLIDE -> sharedAxisX(transition.forward, slideDistance)
        TransitionKind.SLIDE_UP -> slideUp(transition.forward)
        TransitionKind.FADE -> fade()
    }

    private fun sharedAxisX(forward: Boolean, slideDistance: Int): ContentTransform {
        val duration = NavigationConstants.MOTION_DURATION_MS
        val outgoing = (duration * NavigationConstants.OUTGOING_SHARE).toInt()
        val incoming = duration - outgoing
        return (
            slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { if (forward) slideDistance else -slideDistance } +
                fadeIn(tween(incoming, delayMillis = outgoing, easing = LinearOutSlowInEasing))
            ) togetherWith (
            slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { if (forward) -slideDistance else slideDistance } +
                fadeOut(tween(outgoing, easing = FastOutLinearInEasing))
            )
    }

    private fun slideUp(forward: Boolean): ContentTransform {
        val spec = tween<IntOffset>(NavigationConstants.MOTION_DURATION_MS, easing = FastOutSlowInEasing)
        return if (forward) {
            ContentTransform(
                targetContentEnter = slideInVertically(spec) { it },
                initialContentExit = slideOutVertically(spec) { 0 },
            )
        } else {
            ContentTransform(
                targetContentEnter = EnterTransition.None,
                initialContentExit = slideOutVertically(spec) { it },
                targetContentZIndex = NavigationConstants.BACKWARD_Z_INDEX,
            )
        }
    }

    private fun fade(): ContentTransform =
        fadeIn(tween(NavigationConstants.MOTION_DURATION_MS)) togetherWith fadeOut(tween(NavigationConstants.MOTION_DURATION_MS))
}
