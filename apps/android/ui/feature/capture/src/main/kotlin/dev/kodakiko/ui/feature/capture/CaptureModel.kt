package dev.kodakiko.ui.feature.capture

import android.content.res.Resources
import dev.kodakiko.service.entries.ImportRefusal
import dev.kodakiko.service.entries.MediaInput
import dev.kodakiko.ui.core.R

fun captureActionsEnabled(items: Int, pending: Int, recording: Boolean): CaptureActionsEnabled {
    val ready = items > 0 && !recording
    return CaptureActionsEnabled(next = ready, addToEntry = ready && pending == 0)
}

fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / CaptureConstants.MS_PER_SECOND
    val minutes = totalSeconds / CaptureConstants.SECONDS_PER_MINUTE
    val seconds = totalSeconds % CaptureConstants.SECONDS_PER_MINUTE
    return minutes.toString().padStart(CaptureConstants.TIMER_DIGITS, CaptureConstants.TIMER_PAD) +
        CaptureConstants.TIMER_SEPARATOR +
        seconds.toString().padStart(CaptureConstants.TIMER_DIGITS, CaptureConstants.TIMER_PAD)
}

fun refusalLabel(input: MediaInput): RefusalLabel {
    input.fileName?.let { return RefusalLabel.Named(it) }
    val name = input.file.name.lowercase()
    return if (CaptureConstants.VIDEO_FILE_EXTENSIONS.any { name.endsWith(it) }) RefusalLabel.ThisVideo else RefusalLabel.ThisPhoto
}

private fun labelText(resources: Resources, label: RefusalLabel): String = when (label) {
    is RefusalLabel.Named -> label.name
    RefusalLabel.ThisPhoto -> resources.getString(R.string.capture_this_photo)
    RefusalLabel.ThisVideo -> resources.getString(R.string.capture_this_video)
}

fun refusalText(resources: Resources, refusal: CaptureRefusal): String {
    val label = labelText(resources, refusal.label)
    return when (refusal.reason) {
        ImportRefusal.UNSUPPORTED -> resources.getString(R.string.capture_refusal_unsupported, label)
        ImportRefusal.UNDECODABLE -> resources.getString(R.string.capture_refusal_undecodable_native, label)
        ImportRefusal.TOO_LARGE -> resources.getString(R.string.capture_refusal_too_large, label)
        ImportRefusal.TRAY_FULL -> resources.getString(R.string.capture_refusal_tray_full, refusal.max)
        ImportRefusal.READ_FAILED -> resources.getString(R.string.capture_refusal_read_failed, label)
    }
}

internal fun CaptureRefusal.sameText(other: CaptureRefusal): Boolean =
    reason == other.reason && max == other.max && (reason == ImportRefusal.TRAY_FULL || label == other.label)
