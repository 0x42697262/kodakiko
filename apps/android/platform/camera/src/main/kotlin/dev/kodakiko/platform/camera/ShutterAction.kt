package dev.kodakiko.platform.camera

enum class CaptureMode { PHOTO, VIDEO }

enum class ShutterEvent { TAP, HOLD_START, HOLD_END }

enum class ShutterAction { PHOTO, START, START_CAPPED, STOP, NONE }

fun shutterAction(
    mode: CaptureMode,
    event: ShutterEvent,
    holdToRecord: Boolean,
    recording: Boolean,
    heldRecording: Boolean,
): ShutterAction {
    if (event == ShutterEvent.TAP) {
        if (mode == CaptureMode.PHOTO) return ShutterAction.PHOTO
        return if (recording) ShutterAction.STOP else ShutterAction.START
    }
    if (!holdToRecord) {
        if (event == ShutterEvent.HOLD_END) return ShutterAction.NONE
        if (mode == CaptureMode.PHOTO) return ShutterAction.PHOTO
        return if (recording) ShutterAction.STOP else ShutterAction.START
    }
    if (event == ShutterEvent.HOLD_START) return if (recording) ShutterAction.NONE else ShutterAction.START_CAPPED
    return if (heldRecording) ShutterAction.STOP else ShutterAction.NONE
}
