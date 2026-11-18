package dev.kodakiko.service.entries

import dev.kodakiko.js.jsRound

data class CropRect(val x: Double, val y: Double, val width: Double, val height: Double)

enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class PixelCrop(val originX: Int, val originY: Int, val width: Int, val height: Int)

private fun clamp(value: Double, low: Double, high: Double): Double = minOf(high, maxOf(low, value))

fun toPixels(rect: CropRect, imageWidth: Int, imageHeight: Int): PixelCrop {
    val originX = jsRound(rect.x * imageWidth).toInt()
    val originY = jsRound(rect.y * imageHeight).toInt()
    return PixelCrop(
        originX = originX,
        originY = originY,
        width = minOf(imageWidth - originX, jsRound(rect.width * imageWidth).toInt()),
        height = minOf(imageHeight - originY, jsRound(rect.height * imageHeight).toInt()),
    )
}

fun dragCorner(rect: CropRect, corner: CropCorner, pointerX: Double, pointerY: Double): CropRect {
    var left = rect.x
    var top = rect.y
    var right = rect.x + rect.width
    var bottom = rect.y + rect.height
    if (corner == CropCorner.TOP_LEFT || corner == CropCorner.BOTTOM_LEFT) {
        left = clamp(pointerX, 0.0, right - CROP_MIN_FRACTION)
    } else {
        right = clamp(pointerX, left + CROP_MIN_FRACTION, 1.0)
    }
    if (corner == CropCorner.TOP_LEFT || corner == CropCorner.TOP_RIGHT) {
        top = clamp(pointerY, 0.0, bottom - CROP_MIN_FRACTION)
    } else {
        bottom = clamp(pointerY, top + CROP_MIN_FRACTION, 1.0)
    }
    return CropRect(x = left, y = top, width = right - left, height = bottom - top)
}

fun moveCrop(rect: CropRect, deltaX: Double, deltaY: Double): CropRect =
    rect.copy(x = clamp(rect.x + deltaX, 0.0, 1.0 - rect.width), y = clamp(rect.y + deltaY, 0.0, 1.0 - rect.height))
