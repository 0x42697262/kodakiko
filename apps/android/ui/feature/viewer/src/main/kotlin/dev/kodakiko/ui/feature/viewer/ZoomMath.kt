package dev.kodakiko.ui.feature.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min

object ZoomMath {
    fun clampScale(scale: Double): Double = min(ViewerConstants.ZOOM_MAX, max(ViewerConstants.ZOOM_MIN, scale))

    fun settleScale(scale: Double): Double = if (scale < ViewerConstants.ZOOM_SNAP_BELOW) ViewerConstants.ZOOM_UNZOOMED else scale

    fun maxTranslate(displayedSize: Double, viewportSize: Double, scale: Double): Double =
        max(0.0, (displayedSize * scale - viewportSize) / ViewerConstants.HALF)

    fun clampTranslate(value: Double, max: Double): Double = min(max, max(-max, value))

    fun zoomAround(translate: Double, focal: Double, center: Double, scaleRatio: Double): Double =
        translate * scaleRatio + (focal - center) * (1 - scaleRatio)
}

@Stable
class PhotoZoomState(
    private val viewportWidth: Float,
    private val viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
) {
    private val ratio: Float =
        if (imageWidth > 0f && imageHeight > 0f) min(viewportWidth / imageWidth, viewportHeight / imageHeight) else 0f
    private val fittedWidth: Double = if (ratio > 0f) (imageWidth * ratio).toDouble() else viewportWidth.toDouble()
    private val fittedHeight: Double = if (ratio > 0f) (imageHeight * ratio).toDouble() else viewportHeight.toDouble()
    private val centerX: Double = viewportWidth / ViewerConstants.HALF
    private val centerY: Double = viewportHeight / ViewerConstants.HALF

    var scale: Float by mutableFloatStateOf(ViewerConstants.ZOOM_UNZOOMED.toFloat())
        private set

    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > ViewerConstants.ZOOM_UNZOOMED

    fun doubleTap(position: Offset) {
        if (zoomed) {
            reset()
            return
        }
        offset = Offset.Zero
        apply(ViewerConstants.ZOOM_DOUBLE_TAP, position)
    }

    fun pinch(factor: Float, focal: Offset) {
        apply(ZoomMath.clampScale(scale.toDouble() * factor), focal)
    }

    fun endPinch() {
        if (ZoomMath.settleScale(scale.toDouble()) == ViewerConstants.ZOOM_UNZOOMED) reset()
    }

    fun pan(delta: Offset) {
        if (!zoomed) return
        offset = clamped(offset.x + delta.x.toDouble(), offset.y + delta.y.toDouble(), scale.toDouble())
    }

    fun reset() {
        scale = ViewerConstants.ZOOM_UNZOOMED.toFloat()
        offset = Offset.Zero
    }

    private fun apply(next: Double, focal: Offset) {
        val scaleRatio = next / scale
        val x = ZoomMath.zoomAround(offset.x.toDouble(), focal.x.toDouble(), centerX, scaleRatio)
        val y = ZoomMath.zoomAround(offset.y.toDouble(), focal.y.toDouble(), centerY, scaleRatio)
        offset = clamped(x, y, next)
        scale = next.toFloat()
    }

    private fun clamped(x: Double, y: Double, atScale: Double): Offset = Offset(
        ZoomMath.clampTranslate(x, ZoomMath.maxTranslate(fittedWidth, viewportWidth.toDouble(), atScale)).toFloat(),
        ZoomMath.clampTranslate(y, ZoomMath.maxTranslate(fittedHeight, viewportHeight.toDouble(), atScale)).toFloat(),
    )
}
