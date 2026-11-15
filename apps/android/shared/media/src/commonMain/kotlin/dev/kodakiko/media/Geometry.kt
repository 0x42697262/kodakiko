package dev.kodakiko.media

import kotlin.math.floor

data class MediaSize(val width: Int, val height: Int)

internal fun jsRound(value: Double): Double {
    val lower = floor(value)
    return if (value - lower >= JS_ROUND_HALF) lower + 1 else lower
}

fun fitWithin(width: Int, height: Int, maxEdge: Int): MediaSize {
    val longest = maxOf(width, height)
    val scale = if (longest > maxEdge) maxEdge.toDouble() / longest else 1.0
    return MediaSize(
        width = maxOf(1.0, jsRound(width * scale)).toInt(),
        height = maxOf(1.0, jsRound(height * scale)).toInt(),
    )
}

fun displaySize(width: Int, height: Int, orientation: Int?): MediaSize {
    val swapped = orientation != null && orientation >= ORIENTATION_SWAP_MIN && orientation <= ORIENTATION_MAX
    return if (swapped) MediaSize(width = height, height = width) else MediaSize(width, height)
}
