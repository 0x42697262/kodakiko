package dev.kodakiko.ui.core.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

@Immutable
data class EdgeInsets(val top: Dp, val bottom: Dp, val left: Dp, val right: Dp)

val LocalFullScreen: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

fun systemInsets(insets: EdgeInsets, fullScreen: Boolean): EdgeInsets {
    if (!fullScreen) return insets
    return insets.copy(
        top = max(insets.top, SystemInsetsConstants.FULL_SCREEN_MIN_TOP),
        bottom = max(insets.bottom, SystemInsetsConstants.FULL_SCREEN_MIN_BOTTOM),
    )
}

@Composable
fun systemWindowInsets(): WindowInsets {
    val base = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val fullScreen = LocalFullScreen.current
    return remember(base, fullScreen) { FullScreenWindowInsets(base, fullScreen) }
}

fun Modifier.systemInsetsPadding(): Modifier = composed { windowInsetsPadding(systemWindowInsets()) }

private class FullScreenWindowInsets(
    private val base: WindowInsets,
    private val fullScreen: Boolean,
) : WindowInsets {
    private fun resolved(density: Density): EdgeInsets = with(density) {
        systemInsets(
            EdgeInsets(
                top = base.getTop(density).toDp(),
                bottom = base.getBottom(density).toDp(),
                left = 0.dp,
                right = 0.dp,
            ),
            fullScreen,
        )
    }

    override fun getTop(density: Density): Int = with(density) { resolved(density).top.roundToPx() }

    override fun getBottom(density: Density): Int = with(density) { resolved(density).bottom.roundToPx() }

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = base.getLeft(density, layoutDirection)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = base.getRight(density, layoutDirection)
}
