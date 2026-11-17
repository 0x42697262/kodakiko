package dev.kodakiko.ui.feature.capture

import dev.kodakiko.platform.camera.CameraConstants
import dev.kodakiko.service.entries.ImportRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureModelTest {
    private fun text(reason: ImportRefusal, label: RefusalLabel, max: Int = 20): String = refusalText(resources, CaptureRefusal(reason, label, max))

    @Test
    fun nextNeedsAtLeastOneItemAndNothingRecording() {
        assertTrue(captureActionsEnabled(items = 1, pending = 0, recording = false).next)
        assertFalse(captureActionsEnabled(items = 0, pending = 0, recording = false).next)
    }

    @Test
    fun nextAndAddToEntryAreOffWhileAVideoIsRecording() {
        assertEquals(CaptureActionsEnabled(next = false, addToEntry = false), captureActionsEnabled(items = 2, pending = 0, recording = true))
    }

    @Test
    fun addToEntryWaitsForPendingImportsButNextDoesNot() {
        assertFalse(captureActionsEnabled(items = 2, pending = 1, recording = false).addToEntry)
        assertTrue(captureActionsEnabled(items = 2, pending = 1, recording = false).next)
        assertTrue(captureActionsEnabled(items = 2, pending = 0, recording = false).addToEntry)
        assertFalse(captureActionsEnabled(items = 0, pending = 3, recording = false).next)
    }

    @Test
    fun theStateExposesTheSameRules() {
        val ready = CaptureStates.idle.copy(items = CaptureStates.items(2))
        assertTrue(ready.canNext)
        assertTrue(ready.canAddToEntry)
        assertFalse(ready.copy(pending = 1).canAddToEntry)
        assertTrue(ready.copy(pending = 1).canNext)
        assertFalse(ready.copy(recording = CaptureRecording(elapsedMs = 0)).canNext)
        assertFalse(ready.copy(recording = CaptureRecording(elapsedMs = 0)).canAddToEntry)
        assertFalse(CaptureStates.idle.canNext)
    }

    @Test
    fun theTimerReadsMinutesAndSecondsWithTwoDigits() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:00", formatDuration(999))
        assertEquals("00:01", formatDuration(1_000))
        assertEquals("00:59", formatDuration(59_999))
        assertEquals("01:00", formatDuration(60_000))
        assertEquals("01:01", formatDuration(61_250))
        assertEquals("10:05", formatDuration(605_000))
        assertEquals("120:00", formatDuration(7_200_000))
        assertEquals("00:00", formatDuration(-5_000))
    }

    @Test
    fun namesAnUnsupportedFile() {
        assertEquals("notes.pdf isn't a supported photo or video.", text(ImportRefusal.UNSUPPORTED, RefusalLabel.Named("notes.pdf")))
    }

    @Test
    fun saysAFileCouldNotBeOpenedOnAndroid() {
        assertEquals("IMG_0001.heic couldn't be opened.", text(ImportRefusal.UNDECODABLE, RefusalLabel.Named("IMG_0001.heic")))
    }

    @Test
    fun saysAFileIsLargerThanTheServerAllows() {
        assertEquals("clip.mp4 is larger than this server allows.", text(ImportRefusal.TOO_LARGE, RefusalLabel.Named("clip.mp4")))
    }

    @Test
    fun saysHowManyItemsAnEntryCanHold() {
        assertEquals("An entry can hold up to 20 photos and videos.", text(ImportRefusal.TRAY_FULL, RefusalLabel.Named("a.jpg"), 20))
        assertEquals("An entry can hold up to 3 photos and videos.", text(ImportRefusal.TRAY_FULL, RefusalLabel.ThisPhoto, 3))
    }

    @Test
    fun asksToTryAgainAfterAReadFailure() {
        assertEquals("clip.mp4 couldn't be added. Try again.", text(ImportRefusal.READ_FAILED, RefusalLabel.Named("clip.mp4")))
    }

    @Test
    fun namesCameraCapturesAsThisPhotoAndThisVideo() {
        assertEquals("This photo couldn't be added. Try again.", text(ImportRefusal.READ_FAILED, RefusalLabel.ThisPhoto))
        assertEquals("This video couldn't be opened.", text(ImportRefusal.UNDECODABLE, RefusalLabel.ThisVideo))
    }

    @Test
    fun aLabelIsTheFileNameElseThisVideoForVideoExtensionsElseThisPhoto() {
        assertEquals(RefusalLabel.Named("Holiday.JPG"), refusalLabel(picked("Holiday.JPG")))
        assertEquals(RefusalLabel.Named("clip.mp4"), refusalLabel(picked("clip.mp4")))
        assertEquals(RefusalLabel.ThisPhoto, refusalLabel(cameraPhoto("capture-1.jpg")))
        assertEquals(RefusalLabel.ThisVideo, refusalLabel(cameraVideo("capture-2.mp4")))
        assertEquals(RefusalLabel.ThisVideo, refusalLabel(cameraVideo("capture-3.MOV")))
        assertEquals(RefusalLabel.ThisVideo, refusalLabel(cameraVideo("capture-4.webm")))
        assertEquals(RefusalLabel.ThisPhoto, refusalLabel(picked(null, "phone-0.jpg")))
        assertEquals(RefusalLabel.ThisPhoto, refusalLabel(picked(null, "clip.mp4.jpg")))
    }

    @Test
    fun captureTimingsMatchV031() {
        assertEquals(300L, CameraConstants.HOLD_TO_RECORD_MS)
        assertEquals(250L, CaptureConstants.RECORDING_TIMER_INTERVAL_MS)
        assertEquals(5_000L, CaptureConstants.LOCATION_TIMEOUT_MS)
    }
}
