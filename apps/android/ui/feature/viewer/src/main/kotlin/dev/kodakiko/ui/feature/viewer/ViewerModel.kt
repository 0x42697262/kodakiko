package dev.kodakiko.ui.feature.viewer

import androidx.media3.common.PlaybackException
import dev.kodakiko.crypto.CryptoException
import dev.kodakiko.js.jsonStringifyNumber

data class AutoplaySettings(val autoplayVideos: Boolean, val autoplayOnMobileData: Boolean)

data class AutoplayState(val active: Boolean, val cached: Boolean, val wifi: Boolean)

fun shouldAutoplay(settings: AutoplaySettings, state: AutoplayState): Boolean {
    if (!settings.autoplayVideos || !state.active) return false
    return state.cached || state.wifi || settings.autoplayOnMobileData
}

fun mapUri(latitude: Double, longitude: Double): String {
    val point = jsonStringifyNumber(latitude) + ViewerConstants.GEO_SEPARATOR + jsonStringifyNumber(longitude)
    return ViewerConstants.GEO_SCHEME + point + ViewerConstants.GEO_QUERY + point
}

enum class VideoProblem(val retry: Boolean) {
    DOWNLOAD_FAILED(true),
    DAMAGED(false),
    FORMAT_UNSUPPORTED(false),
    STOPPED(true),
}

fun videoProblemOf(error: Throwable): VideoProblem = if (error is CryptoException) VideoProblem.DAMAGED else VideoProblem.DOWNLOAD_FAILED

fun playbackProblemOf(errorCode: Int): VideoProblem = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> VideoProblem.STOPPED
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> VideoProblem.DAMAGED
    else -> VideoProblem.FORMAT_UNSUPPORTED
}
