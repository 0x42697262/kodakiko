// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kodakiko.ui.core.testTagsAsResourceIdRoot
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enableImplicitDismiss: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isTabletUi = isTabletUi()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = dialogProperties,
    ) {
        AdaptiveSheetImpl(
            isTabletUi = isTabletUi,
            enableImplicitDismiss = enableImplicitDismiss,
            onDismissRequest = onDismissRequest,
            modifier = modifier.testTagsAsResourceIdRoot(),
        ) {
            content()
        }
    }
}

@Composable
@ReadOnlyComposable
private fun isTabletUi(): Boolean =
    LocalConfiguration.current.smallestScreenWidthDp >= ComponentConstants.TABLET_UI_MIN_SCREEN_WIDTH_DP

@Composable
private fun AdaptiveSheetImpl(
    isTabletUi: Boolean,
    enableImplicitDismiss: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    if (isTabletUi) {
        var targetAlpha by remember { mutableFloatStateOf(0f) }
        val alpha by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = sheetAnimationSpec,
            label = "alpha",
        )
        val internalOnDismissRequest: () -> Unit = {
            scope.launch {
                targetAlpha = 0f
                onDismissRequest()
            }
        }
        Box(
            modifier = Modifier
                .dismissOnTap(enabled = enableImplicitDismiss, onTap = internalOnDismissRequest)
                .fillMaxSize()
                .alpha(alpha),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .requiredWidthIn(max = ComponentConstants.SHEET_MAX_WIDTH)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    )
                    .systemBarsPadding()
                    .padding(vertical = ComponentConstants.SHEET_TABLET_VERTICAL_PADDING)
                    .then(modifier),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    BackHandler(
                        enabled = enableImplicitDismiss && remember { derivedStateOf { alpha > 0f } }.value,
                        onBack = internalOnDismissRequest,
                    )
                    content()
                },
            )

            LaunchedEffect(Unit) {
                targetAlpha = 1f
            }
        }
    } else {
        val anchoredDraggableState = rememberSaveable(saver = AnchoredDraggableState.Saver()) {
            AnchoredDraggableState(initialValue = SHEET_CLOSED)
        }
        val dismissVelocity = with(density) { ComponentConstants.SHEET_DISMISS_VELOCITY.toPx() }
        val flingBehavior = remember(anchoredDraggableState, dismissVelocity) {
            SheetFlingBehavior(anchoredDraggableState, dismissVelocity)
        }
        val internalOnDismissRequest: () -> Unit = {
            if (anchoredDraggableState.settledValue == SHEET_OPEN) {
                scope.launch { anchoredDraggableState.animateTo(SHEET_CLOSED, sheetAnimationSpec) }
            }
        }
        Box(
            modifier = Modifier
                .dismissOnTap(enabled = enableImplicitDismiss, onTap = internalOnDismissRequest)
                .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = ComponentConstants.SHEET_MAX_WIDTH)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    )
                    .onSizeChanged {
                        val anchors = DraggableAnchors {
                            SHEET_OPEN at 0f
                            SHEET_CLOSED at it.height.toFloat()
                        }
                        anchoredDraggableState.updateAnchors(anchors)
                    }
                    .then(
                        if (enableImplicitDismiss) {
                            Modifier.nestedScroll(
                                remember(anchoredDraggableState, flingBehavior) {
                                    anchoredDraggableState.preUpPostDownNestedScrollConnection { velocity ->
                                        scope.launch { flingBehavior.settle(velocity) }
                                    }
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(modifier)
                    .offset {
                        IntOffset(
                            0,
                            anchoredDraggableState.offset
                                .takeIf { it.isFinite() }
                                ?.roundToInt()
                                ?: 0,
                        )
                    }
                    .anchoredDraggable(
                        state = anchoredDraggableState,
                        orientation = Orientation.Vertical,
                        enabled = enableImplicitDismiss,
                        flingBehavior = flingBehavior,
                    )
                    .navigationBarsPadding()
                    .statusBarsPadding(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = {
                    BackHandler(
                        enabled = enableImplicitDismiss && anchoredDraggableState.targetValue == SHEET_OPEN,
                        onBack = internalOnDismissRequest,
                    )
                    content()
                },
            )

            LaunchedEffect(anchoredDraggableState) {
                scope.launch { anchoredDraggableState.animateTo(SHEET_OPEN, sheetAnimationSpec) }
                snapshotFlow { anchoredDraggableState.settledValue }
                    .drop(1)
                    .filter { it == SHEET_CLOSED }
                    .collectLatest {
                        onDismissRequest()
                    }
            }
        }
    }
}

@Composable
private fun Modifier.dismissOnTap(enabled: Boolean, onTap: () -> Unit): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    return if (enabled) pointerInput(Unit) { detectTapGestures { currentOnTap() } } else this
}

private class SheetFlingBehavior(
    private val state: AnchoredDraggableState<Int>,
    private val dismissVelocity: Float,
) : FlingBehavior {
    fun targetFor(velocity: Float): Int {
        val closedOffset = state.anchors.positionOf(SHEET_CLOSED)
        val offset = state.offset
        return when {
            !closedOffset.isFinite() || !offset.isFinite() -> state.settledValue
            velocity >= dismissVelocity -> SHEET_CLOSED
            velocity <= -dismissVelocity -> SHEET_OPEN
            offset >= closedOffset * ComponentConstants.SHEET_DISMISS_FRACTION -> SHEET_CLOSED
            else -> SHEET_OPEN
        }
    }

    suspend fun settle(velocity: Float) {
        state.animateTo(targetFor(velocity), sheetAnimationSpec)
    }

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val target = state.anchors.positionOf(targetFor(initialVelocity))
        val distance = target - state.offset
        if (!distance.isFinite() || abs(distance) == 0f) return 0f
        var scrolled = 0f
        animate(initialValue = 0f, targetValue = distance, animationSpec = sheetAnimationSpec) { value, _ ->
            scrolled += scrollBy(value - scrolled)
        }
        return 0f
    }
}

private fun <T> AnchoredDraggableState<T>.preUpPostDownNestedScrollConnection(
    onFling: (velocity: Float) -> Unit,
) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.toFloat()
        return if (delta < 0 && source == NestedScrollSource.UserInput) {
            dispatchRawDelta(delta).toOffset()
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        return if (source == NestedScrollSource.UserInput) {
            dispatchRawDelta(available.toFloat()).toOffset()
        } else {
            Offset.Zero
        }
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val toFling = available.toFloat()
        return if (toFling < 0 && offset > anchors.minPosition()) {
            onFling(toFling)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        onFling(available.toFloat())
        return if (targetValue != settledValue) {
            available
        } else {
            Velocity.Zero
        }
    }

    private fun Float.toOffset(): Offset = Offset(0f, this)

    @JvmName("velocityToFloat")
    private fun Velocity.toFloat() = this.y

    @JvmName("offsetToFloat")
    private fun Offset.toFloat(): Float = this.y
}

private const val SHEET_OPEN = 0
private const val SHEET_CLOSED = 1

private val sheetAnimationSpec: AnimationSpec<Float> = tween(durationMillis = ComponentConstants.SHEET_ANIMATION_MILLIS)

private val dialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = true,
)
