package dev.kodakiko.platform.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterActionTest {
    private data class Case(
        val mode: CaptureMode,
        val event: ShutterEvent,
        val holdToRecord: Boolean,
        val recording: Boolean,
        val heldRecording: Boolean,
        val expected: ShutterAction,
    )

    private val cases = listOf(
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, false, false, false, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, false, false, true, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, false, true, false, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, false, true, true, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, true, false, false, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, true, false, true, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, true, true, false, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.TAP, true, true, true, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, false, false, false, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, false, false, true, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, false, true, false, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, false, true, true, ShutterAction.PHOTO),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, true, false, false, ShutterAction.START_CAPPED),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, true, false, true, ShutterAction.START_CAPPED),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, true, true, false, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_START, true, true, true, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, false, false, false, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, false, false, true, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, false, true, false, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, false, true, true, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, true, false, false, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, true, false, true, ShutterAction.STOP),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, true, true, false, ShutterAction.NONE),
        Case(CaptureMode.PHOTO, ShutterEvent.HOLD_END, true, true, true, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, false, false, false, ShutterAction.START),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, false, false, true, ShutterAction.START),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, false, true, false, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, false, true, true, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, true, false, false, ShutterAction.START),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, true, false, true, ShutterAction.START),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, true, true, false, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.TAP, true, true, true, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, false, false, false, ShutterAction.START),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, false, false, true, ShutterAction.START),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, false, true, false, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, false, true, true, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, true, false, false, ShutterAction.START_CAPPED),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, true, false, true, ShutterAction.START_CAPPED),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, true, true, false, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_START, true, true, true, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, false, false, false, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, false, false, true, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, false, true, false, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, false, true, true, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, true, false, false, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, true, false, true, ShutterAction.STOP),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, true, true, false, ShutterAction.NONE),
        Case(CaptureMode.VIDEO, ShutterEvent.HOLD_END, true, true, true, ShutterAction.STOP),
    )

    @Test
    fun everyCombinationMatchesV031() {
        assertEquals(48, cases.size)
        for (case in cases) {
            assertEquals(case.toString(), case.expected, shutterAction(case.mode, case.event, case.holdToRecord, case.recording, case.heldRecording))
        }
    }

    @Test
    fun theHoldThresholdIsV031s() {
        assertEquals(300L, CameraConstants.HOLD_TO_RECORD_MS)
        assertEquals(92, CameraConstants.PHOTO_JPEG_QUALITY)
    }
}
